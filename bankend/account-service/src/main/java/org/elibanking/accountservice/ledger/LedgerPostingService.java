package org.elibanking.accountservice.ledger;

import org.elibanking.common.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The only class that writes {@code ledger_account.balance} and {@code journal_*} (wallet-plan 3.1). An ArchUnit test
 * enforces that.
 *
 * <p>Order of one posting, all in one database transaction:
 * <ol>
 *   <li>validate the request (every line &gt; 0, debits = credits per currency);</li>
 *   <li>{@code SELECT … FOR UPDATE} the ledger accounts, in ascending id order;</li>
 *   <li>check the idempotency key <em>under those locks</em>: an existing key returns the original entry, or is a
 *       conflict if the content differs;</li>
 *   <li>check the funds rule (customer accounts never go below zero; SYSTEM accounts may);</li>
 *   <li>insert the entry and its lines, then update the cached balances.</li>
 * </ol>
 * The idempotency check comes after the locks, not before them as wallet-plan 3.2 lists it. Two requests with the same
 * key race on the same accounts, so checking before the lock would let both pass the check and one would fail on the
 * unique key. Checking under the lock makes the second one see the first one's entry.
 */
@Service
public class LedgerPostingService {

    private static final int KEY_MAX_LENGTH = 100;
    private static final int TYPE_MAX_LENGTH = 50;

    private final JdbcTemplate jdbc;

    public LedgerPostingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PostingResult post(PostingRequest request) {
        validate(request);
        Map<Long, LockedAccount> accounts = lockAccounts(request.lines());

        Optional<Long> existing = findEntryIdByKey(request.idempotencyKey());
        if (existing.isPresent()) {
            requireSameContent(existing.get(), request);
            return new PostingResult(existing.get(), true);
        }
        if (request.reversesEntryId() != null) {
            requireReversible(request.reversesEntryId());
        }

        Map<Long, BigDecimal> deltas = balanceDeltas(request.lines(), accounts);
        requireFunds(deltas, accounts);

        long entryId = insertEntry(request);
        insertLines(entryId, request.lines());
        applyBalances(deltas, accounts);
        return new PostingResult(entryId, false);
    }

    /**
     * Posts the opposite lines of an existing entry and links them through {@code reverses_entry_id}. The unique key
     * on that column means an entry can only be reversed once.
     */
    @Transactional
    public PostingResult reverse(long entryId, String idempotencyKey, String createdBy) {
        requireEntryExists(entryId);
        List<PostingLine> reversed = jdbc.query(
                "SELECT ledger_account_id, direction, amount, currency FROM journal_line WHERE entry_id = ? ORDER BY id",
                (rs, n) -> new PostingLine(
                        rs.getLong("ledger_account_id"),
                        Side.valueOf(rs.getString("direction")).opposite(),
                        Money.of(rs.getBigDecimal("amount"), rs.getString("currency"))),
                entryId);
        return post(new PostingRequest(
                "REVERSAL", idempotencyKey, "reversal of " + entryId, null, createdBy, reversed, entryId));
    }

    // ---------------------------------------------------------------- validation

    private static void validate(PostingRequest request) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > KEY_MAX_LENGTH) {
            throw new InvalidPostingException("idempotencyKey is required (at most " + KEY_MAX_LENGTH + " characters)");
        }
        if (request.type() == null || request.type().isBlank() || request.type().length() > TYPE_MAX_LENGTH) {
            throw new InvalidPostingException("type is required (at most " + TYPE_MAX_LENGTH + " characters)");
        }
        if (request.lines().isEmpty()) {
            throw new InvalidPostingException("an entry needs at least one line");
        }
        Map<String, BigDecimal> debits = new TreeMap<>();
        Map<String, BigDecimal> credits = new TreeMap<>();
        for (PostingLine line : request.lines()) {
            if (line.amount().signum() <= 0) {
                throw new InvalidPostingException("every line amount must be greater than 0");
            }
            Map<String, BigDecimal> side = line.side() == Side.DEBIT ? debits : credits;
            side.merge(line.amount().currencyCode(), line.amount().amount(), BigDecimal::add);
        }
        Set<String> currencies = new TreeSet<>(debits.keySet());
        currencies.addAll(credits.keySet());
        for (String currency : currencies) {
            BigDecimal d = debits.getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal c = credits.getOrDefault(currency, BigDecimal.ZERO);
            if (d.compareTo(c) != 0) {
                throw new InvalidPostingException("entry is unbalanced in " + currency + ": debits "
                        + d.toPlainString() + ", credits " + c.toPlainString());
            }
        }
    }

    // ---------------------------------------------------------------- locking

    private Map<Long, LockedAccount> lockAccounts(List<PostingLine> lines) {
        TreeSet<Long> ids = lines.stream().map(PostingLine::ledgerAccountId)
                .collect(Collectors.toCollection(TreeSet::new));
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        List<LockedAccount> rows = jdbc.query(
                "SELECT id, owner_type, type, currency, balance FROM ledger_account WHERE id IN (" + placeholders
                        + ") ORDER BY id FOR UPDATE",
                (rs, n) -> new LockedAccount(
                        rs.getLong("id"),
                        OwnerType.valueOf(rs.getString("owner_type")),
                        AccountType.valueOf(rs.getString("type")),
                        rs.getString("currency"),
                        rs.getBigDecimal("balance")),
                ids.toArray());
        if (rows.size() != ids.size()) {
            throw new LedgerNotFoundException("ledger account not found in " + ids);
        }
        Map<Long, LockedAccount> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.id(), row));
        for (PostingLine line : lines) {
            LockedAccount account = byId.get(line.ledgerAccountId());
            if (!account.currency().equals(line.amount().currencyCode())) {
                throw new InvalidPostingException("line currency " + line.amount().currencyCode()
                        + " does not match ledger account " + account.id() + " (" + account.currency() + ")");
            }
        }
        return byId;
    }

    // ---------------------------------------------------------------- idempotency

    private Optional<Long> findEntryIdByKey(String idempotencyKey) {
        return jdbc.queryForList("SELECT id FROM journal_entry WHERE idempotency_key = ?", Long.class, idempotencyKey)
                .stream().findFirst();
    }

    private void requireSameContent(long entryId, PostingRequest request) {
        Map<String, Object> head = jdbc.queryForMap(
                "SELECT type, reference, reverses_entry_id FROM journal_entry WHERE id = ?", entryId);
        Object storedReverses = head.get("reverses_entry_id");
        Long reverses = storedReverses == null ? null : ((Number) storedReverses).longValue();
        boolean sameHead = request.type().equals(head.get("type"))
                && Objects.equals(request.reference(), head.get("reference"))
                && Objects.equals(request.reversesEntryId(), reverses);

        List<String> stored = new ArrayList<>(jdbc.query(
                "SELECT ledger_account_id, direction, amount, currency FROM journal_line WHERE entry_id = ?",
                (rs, n) -> lineKey(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)),
                entryId));
        List<String> wanted = request.lines().stream()
                .map(l -> lineKey(l.ledgerAccountId(), l.side().name(), l.amount().amount(),
                        l.amount().currencyCode()))
                .collect(Collectors.toCollection(ArrayList::new));
        stored.sort(null);
        wanted.sort(null);

        if (!sameHead || !stored.equals(wanted)) {
            throw new IdempotencyConflictException("idempotency key " + request.idempotencyKey()
                    + " was already used for a different entry");
        }
    }

    private static String lineKey(long accountId, String side, BigDecimal amount, String currency) {
        return accountId + "|" + side + "|" + amount.setScale(Money.STORAGE_SCALE, RoundingMode.UNNECESSARY)
                .toPlainString() + "|" + currency;
    }

    private void requireReversible(long entryId) {
        requireEntryExists(entryId);
        Long reversals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM journal_entry WHERE reverses_entry_id = ?", Long.class, entryId);
        if (reversals != null && reversals > 0) {
            throw new AlreadyReversedException("journal entry " + entryId + " has already been reversed");
        }
    }

    private void requireEntryExists(long entryId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry WHERE id = ?", Long.class, entryId);
        if (count == null || count == 0) {
            throw new LedgerNotFoundException("journal entry " + entryId + " not found");
        }
    }

    // ---------------------------------------------------------------- funds and balances

    /** Signed change to each account's cached balance, on that account's normal side (wallet-plan 3.2). */
    private static Map<Long, BigDecimal> balanceDeltas(List<PostingLine> lines, Map<Long, LockedAccount> accounts) {
        Map<Long, BigDecimal> deltas = new TreeMap<>();
        for (PostingLine line : lines) {
            AccountType type = accounts.get(line.ledgerAccountId()).type();
            BigDecimal amount = line.amount().amount().setScale(Money.STORAGE_SCALE, RoundingMode.UNNECESSARY);
            boolean increases = (line.side() == Side.DEBIT) == type.debitNormal();
            deltas.merge(line.ledgerAccountId(), increases ? amount : amount.negate(), BigDecimal::add);
        }
        return deltas;
    }

    private static void requireFunds(Map<Long, BigDecimal> deltas, Map<Long, LockedAccount> accounts) {
        for (Map.Entry<Long, BigDecimal> delta : deltas.entrySet()) {
            LockedAccount account = accounts.get(delta.getKey());
            if (account.ownerType() == OwnerType.USER
                    && account.balance().add(delta.getValue()).signum() < 0) {
                throw new InsufficientFundsException("insufficient funds on ledger account " + account.id());
            }
        }
    }

    private long insertEntry(PostingRequest request) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO journal_entry (type, reference, idempotency_key, correlation_id, created_by,"
                            + " reverses_entry_id) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, request.type());
            ps.setString(2, request.reference());
            ps.setString(3, request.idempotencyKey());
            ps.setString(4, request.correlationId());
            ps.setString(5, request.createdBy());
            if (request.reversesEntryId() == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, request.reversesEntryId());
            }
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey(), "no generated id for journal_entry").longValue();
    }

    private void insertLines(long entryId, List<PostingLine> lines) {
        jdbc.batchUpdate(
                "INSERT INTO journal_line (entry_id, ledger_account_id, direction, amount, currency)"
                        + " VALUES (?, ?, ?, ?, ?)",
                lines, lines.size(),
                (ps, line) -> {
                    ps.setLong(1, entryId);
                    ps.setLong(2, line.ledgerAccountId());
                    ps.setString(3, line.side().name());
                    ps.setBigDecimal(4, line.amount().toStorage());
                    ps.setString(5, line.amount().currencyCode());
                });
    }

    private void applyBalances(Map<Long, BigDecimal> deltas, Map<Long, LockedAccount> accounts) {
        List<Object[]> updates = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> delta : deltas.entrySet()) {
            LockedAccount account = accounts.get(delta.getKey());
            BigDecimal newBalance = account.balance().add(delta.getValue());
            updates.add(new Object[] {newBalance, delta.getKey()});
            if (account.ownerType() == OwnerType.USER) {
                // Expand (3.2): accounts.balance mirrors its wallet in the same transaction, so the old code and the
                // README keep working until the old balance paths are removed. accounts.balance is DECIMAL(15,2) (D12).
                jdbc.update("UPDATE accounts SET balance = ? WHERE ledger_account_id = ?",
                        newBalance.setScale(2, RoundingMode.UNNECESSARY), delta.getKey());
            }
        }
        jdbc.batchUpdate("UPDATE ledger_account SET balance = ?, version = version + 1 WHERE id = ?", updates);
    }

    /**
     * Opens the wallet of an account that has just been saved (wallet-plan 3.4). The wallet row, the link
     * accounts.ledger_account_id, and, for a positive deposit, the opening entry DR OPENING_BALANCE_EQUITY, CR wallet
     * (key acct-{id}-opening) all happen in the caller's transaction, so an account never exists without its wallet. The
     * posting also brings accounts.balance to the deposit, which is the expand mirror.
     */
    @Transactional
    public void openAccount(String accountId, String currency, BigDecimal initialDeposit) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ledger_account (owner_type, owner_ref, type, currency, balance, version)"
                            + " VALUES ('USER', ?, 'LIABILITY', ?, 0, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, accountId);
            ps.setString(2, currency);
            return ps;
        }, keys);
        long wallet = Objects.requireNonNull(keys.getKey(), "no generated id for ledger_account").longValue();
        jdbc.update("UPDATE accounts SET ledger_account_id = ? WHERE id = ?", wallet, accountId);

        if (initialDeposit != null && initialDeposit.signum() > 0) {
            long equity = jdbc.queryForObject("SELECT id FROM ledger_account WHERE code = ?", Long.class,
                    "OPENING_BALANCE_EQUITY:" + currency);
            Money amount = Money.of(initialDeposit, currency);
            post(PostingRequest.of("OPENING_BALANCE", "acct-" + accountId + "-opening", List.of(
                    new PostingLine(equity, Side.DEBIT, amount),
                    new PostingLine(wallet, Side.CREDIT, amount))));
        }
    }

    // ---------------------------------------------------------------- types

    enum OwnerType {
        USER,
        SYSTEM
    }

    enum AccountType {
        ASSET(true),
        EXPENSE(true),
        LIABILITY(false),
        EQUITY(false),
        REVENUE(false);

        private final boolean debitNormal;

        AccountType(boolean debitNormal) {
            this.debitNormal = debitNormal;
        }

        /** ASSET and EXPENSE grow with a debit. The others grow with a credit. */
        boolean debitNormal() {
            return debitNormal;
        }
    }

    record LockedAccount(long id, OwnerType ownerType, AccountType type, String currency, BigDecimal balance) {
    }
}
