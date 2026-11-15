package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.audit.AuditLogWriter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The only writer of the crypto asset subledger; values are integer smallest units. */
@Service
public class CryptoLedgerService {
    private final JdbcTemplate jdbc;
    private final AuditLogWriter audit;

    public CryptoLedgerService(JdbcTemplate jdbc, AuditLogWriter audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public CryptoLedgerApi.OperationResult apply(CryptoLedgerApi.Operation op, String actor, String requestId) {
        if (op.amountBaseUnits().signum() <= 0 || op.amountBaseUnits().toString().length() > 65) {
            throw new InvalidPostingException("crypto amount must be positive and fit 65 integer digits");
        }
        if (op.feeBaseUnits() != null && (op.feeBaseUnits().signum() < 0 || op.feeBaseUnits().toString().length() > 65)) {
            throw new InvalidPostingException("crypto fee must be non-negative and fit 65 integer digits");
        }
        String hash = hash(String.join("|", op.operation(), op.userId(), op.assetKey(), op.amountBaseUnits().toString(),
                String.valueOf(op.feeAssetKey()), String.valueOf(op.feeBaseUnits()), String.valueOf(op.reference())));
        List<Leg> legs = legs(op);
        ensureAccounts(legs);
        Map<Long, Locked> locked = lockAccounts(legs);
        var existing = jdbc.query("SELECT id, request_hash FROM crypto_journal_entry WHERE idempotency_key=?",
                (rs, row) -> Map.entry(rs.getLong(1), rs.getString(2)), op.idempotencyKey()).stream().findFirst();
        if (existing.isPresent()) {
            if (!MessageDigest.isEqual(existing.get().getValue().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) {
                throw new IdempotencyConflictException("Crypto idempotency key was reused with different operation details");
            }
            return new CryptoLedgerApi.OperationResult(existing.get().getKey(), true);
        }
        validateBalanced(legs);
        Map<Long, BigInteger> deltas = deltas(legs, locked);
        for (var entry : deltas.entrySet()) {
            Locked account = locked.get(entry.getKey());
            if ("USER".equals(account.ownerType()) && account.balance().add(entry.getValue()).signum() < 0) {
                throw new InsufficientFundsException("Insufficient crypto balance");
            }
        }
        Long entryId;
        try {
            jdbc.update("INSERT INTO crypto_journal_entry(type,reference,idempotency_key,request_hash,created_by) VALUES(?,?,?,?,?)",
                    op.operation(), op.reference(), op.idempotencyKey(), hash, actor);
            entryId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } catch (DuplicateKeyException race) {
            throw new IdempotencyConflictException("Crypto operation idempotency key is already in use");
        }
        for (Leg leg : legs) {
            jdbc.update("INSERT INTO crypto_journal_line(entry_id,ledger_account_id,asset_key,direction,amount_base_units) VALUES(?,?,?,?,?)",
                    entryId, accountFor(leg, locked).id(), leg.assetKey(), leg.side(), new java.math.BigDecimal(leg.amount()));
        }
        for (var entry : deltas.entrySet()) {
            jdbc.update("UPDATE crypto_ledger_account SET balance=?,version=version+1 WHERE id=?",
                    new java.math.BigDecimal(locked.get(entry.getKey()).balance().add(entry.getValue())), entry.getKey());
        }
        audit.record(actor, "CRYPTO_LEDGER_" + op.operation(), "CRYPTO_JOURNAL_ENTRY", String.valueOf(entryId), requestId,
                "{\"assetKey\":\"" + safe(op.assetKey()) + "\",\"reference\":\"" + safe(op.reference()) + "\"}");
        return new CryptoLedgerApi.OperationResult(entryId, false);
    }

    @Transactional(readOnly = true)
    public List<CryptoLedgerApi.Balance> balances(String userId) {
        return jdbc.query("""
                SELECT asset_key,
                  COALESCE(MAX(CASE WHEN role='AVAILABLE' THEN balance END),0) available,
                  COALESCE(MAX(CASE WHEN role='RESERVED' THEN balance END),0) reserved
                FROM crypto_ledger_account WHERE owner_type='USER' AND owner_ref=? GROUP BY asset_key ORDER BY asset_key
                """, (rs, row) -> new CryptoLedgerApi.Balance(rs.getString("asset_key"), rs.getBigDecimal("available").toBigIntegerExact().toString(),
                rs.getBigDecimal("reserved").toBigIntegerExact().toString()), userId);
    }

    @Transactional
    public void registerAsset(String assetKey) {
        for (String role : List.of("CUSTODY", "FEE_EXPENSE")) {
            jdbc.update("INSERT IGNORE INTO crypto_ledger_account(asset_key,owner_type,owner_ref,account_key,role) VALUES(?,'SYSTEM',NULL,?,?)",
                    assetKey, "system:" + role, role);
        }
    }

    private List<Leg> legs(CryptoLedgerApi.Operation op) {
        List<Leg> legs = new ArrayList<>();
        BigInteger n = op.amountBaseUnits();
        switch (op.operation()) {
            case "DEPOSIT_CREDIT" -> { legs.add(new Leg(op.assetKey(), "SYSTEM", null, "CUSTODY", "DEBIT", n)); legs.add(new Leg(op.assetKey(), "USER", op.userId(), "AVAILABLE", "CREDIT", n)); }
            case "WITHDRAWAL_RESERVE" -> { legs.add(new Leg(op.assetKey(), "USER", op.userId(), "AVAILABLE", "DEBIT", n)); legs.add(new Leg(op.assetKey(), "USER", op.userId(), "RESERVED", "CREDIT", n)); }
            case "WITHDRAWAL_SETTLE" -> { legs.add(new Leg(op.assetKey(), "USER", op.userId(), "RESERVED", "DEBIT", n)); legs.add(new Leg(op.assetKey(), "SYSTEM", null, "CUSTODY", "CREDIT", n)); }
            case "WITHDRAWAL_RELEASE" -> { legs.add(new Leg(op.assetKey(), "USER", op.userId(), "RESERVED", "DEBIT", n)); legs.add(new Leg(op.assetKey(), "USER", op.userId(), "AVAILABLE", "CREDIT", n)); }
            default -> throw new InvalidPostingException("Unsupported crypto ledger operation");
        }
        if (op.feeBaseUnits() != null && op.feeBaseUnits().signum() > 0) {
            if (!"WITHDRAWAL_SETTLE".equals(op.operation()) || op.feeAssetKey() == null || op.feeAssetKey().isBlank()) {
                throw new InvalidPostingException("A withdrawal fee requires a settled withdrawal and fee asset");
            }
            legs.add(new Leg(op.feeAssetKey(), "SYSTEM", null, "FEE_EXPENSE", "DEBIT", op.feeBaseUnits()));
            legs.add(new Leg(op.feeAssetKey(), "SYSTEM", null, "CUSTODY", "CREDIT", op.feeBaseUnits()));
        }
        return legs;
    }

    private void ensureAccounts(List<Leg> legs) {
        for (Leg leg : legs) {
            if ("USER".equals(leg.ownerType())) {
                jdbc.update("INSERT IGNORE INTO crypto_ledger_account(asset_key,owner_type,owner_ref,account_key,role) VALUES(?,'USER',?,?,?)",
                        leg.assetKey(), leg.ownerRef(), "user:" + leg.ownerRef() + ":" + leg.role(), leg.role());
            } else {
                jdbc.update("INSERT IGNORE INTO crypto_ledger_account(asset_key,owner_type,owner_ref,account_key,role) VALUES(?,'SYSTEM',NULL,?,?)",
                        leg.assetKey(), "system:" + leg.role(), leg.role());
            }
        }
    }

    private Map<Long, Locked> lockAccounts(List<Leg> legs) {
        Map<String, Leg> unique = new LinkedHashMap<>();
        for (Leg l : legs) unique.put(l.assetKey()+"|"+l.ownerType()+"|"+l.ownerRef()+"|"+l.role(), l);
        String where = String.join(" OR ", unique.values().stream().map(l -> "(asset_key=? AND owner_type=? AND " + (l.ownerRef()==null ? "owner_ref IS NULL" : "owner_ref=?") + " AND role=?)").toList());
        List<Object> args = new ArrayList<>();
        for (Leg l : unique.values()) { args.add(l.assetKey()); args.add(l.ownerType()); if (l.ownerRef()!=null) args.add(l.ownerRef()); args.add(l.role()); }
        List<Locked> rows = jdbc.query("SELECT id,asset_key,owner_type,owner_ref,role,balance FROM crypto_ledger_account WHERE " + where + " ORDER BY id FOR UPDATE",
                (rs, row) -> new Locked(rs.getLong("id"),rs.getString("asset_key"),rs.getString("owner_type"),rs.getString("owner_ref"),rs.getString("role"),rs.getBigDecimal("balance").toBigIntegerExact()), args.toArray());
        Map<Long, Locked> result = new LinkedHashMap<>(); rows.forEach(r -> result.put(r.id(), r));
        if (result.size() != unique.size()) throw new IllegalStateException("Crypto ledger account could not be initialized");
        return result;
    }

    private static void validateBalanced(List<Leg> legs) {
        Map<String, BigInteger> balance = new TreeMap<>();
        legs.forEach(l -> balance.merge(l.assetKey(), "DEBIT".equals(l.side()) ? l.amount().negate() : l.amount(), BigInteger::add));
        if (balance.values().stream().anyMatch(v -> v.signum() != 0)) throw new InvalidPostingException("Crypto journal must balance separately for every asset");
    }

    private static Map<Long, BigInteger> deltas(List<Leg> legs, Map<Long, Locked> locked) {
        Map<Long, BigInteger> result = new LinkedHashMap<>();
        for (Leg leg : legs) {
            Locked account = accountFor(leg, locked);
            boolean increases = "DEBIT".equals(leg.side()) ? List.of("CUSTODY", "FEE_EXPENSE").contains(account.role()) : List.of("AVAILABLE", "RESERVED").contains(account.role());
            result.merge(account.id(), increases ? leg.amount() : leg.amount().negate(), BigInteger::add);
        }
        return result;
    }

    private static Locked accountFor(Leg leg, Map<Long, Locked> locked) {
        return locked.values().stream().filter(a -> a.assetKey().equals(leg.assetKey()) && a.ownerType().equals(leg.ownerType())
                && java.util.Objects.equals(a.ownerRef(), leg.ownerRef()) && a.role().equals(leg.role())).findFirst().orElseThrow();
    }

    private static String hash(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String safe(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private record Leg(String assetKey, String ownerType, String ownerRef, String role, String side, BigInteger amount) {}
    private record Locked(long id, String assetKey, String ownerType, String ownerRef, String role, BigInteger balance) {}
}
