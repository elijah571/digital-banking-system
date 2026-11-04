package org.elibanking.accountservice.idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Idempotency records for money-moving requests (wallet-plan 3.6, fix 2).
 *
 * <p>The flow for one request:
 * <ol>
 *   <li>{@link #begin}: insert the key as IN_PROGRESS with a lease, in its own short transaction. If the key already
 *       exists: a different request hash is a mismatch; a DONE record replays its stored response; an IN_PROGRESS
 *       record with a live lease is in progress; an IN_PROGRESS record with an expired lease is taken over.</li>
 *   <li>run the handler. Every ledger write it makes uses a deterministic key, so running it again is safe.</li>
 *   <li>{@link #complete}: store the response and mark the record DONE.</li>
 * </ol>
 * The lease is judged on the database clock, so application clocks cannot disagree about it.
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return empty when this request may run the handler; otherwise the stored response to replay
     * @throws IdempotencyKeyMismatchException if the key was used for a different request
     * @throws IdempotencyInProgressException  if another attempt holds a live lease
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<StoredResponse> begin(String scope, String key, String requestHash, Duration lease) {
        Objects.requireNonNull(lease, "lease");
        long leaseMicros = lease.toNanos() / 1000;
        try {
            jdbc.update("INSERT INTO idempotency_record (scope, idem_key, request_hash, status, lease_until)"
                            + " VALUES (?, ?, ?, 'IN_PROGRESS', TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)))",
                    scope, key, requestHash, leaseMicros);
            return Optional.empty();
        } catch (DuplicateKeyException existing) {
            Row row = lockRow(scope, key);
            if (!row.requestHash().equals(requestHash)) {
                throw new IdempotencyKeyMismatchException("idempotency key " + key
                        + " was already used for a different request");
            }
            if (row.done()) {
                return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
            }
            if (row.leaseLive()) {
                throw new IdempotencyInProgressException("a request with idempotency key " + key
                        + " is still in progress; retry after its lease expires");
            }
            // The previous attempt died holding the key. Take it over: its ledger writes are idempotent, so
            // running the handler again cannot move money twice.
            jdbc.update("UPDATE idempotency_record SET lease_until = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6))"
                    + " WHERE scope = ? AND idem_key = ? AND status = 'IN_PROGRESS'", leaseMicros, scope, key);
            return Optional.empty();
        }
    }

    /** Stores the response and marks the record DONE. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String scope, String key, int responseStatus, String responseBodyJson) {
        int updated = jdbc.update("UPDATE idempotency_record SET status = 'DONE', response_status = ?, response_body = ?"
                + " WHERE scope = ? AND idem_key = ? AND status = 'IN_PROGRESS'",
                responseStatus, responseBodyJson, scope, key);
        if (updated != 1) {
            throw new IllegalStateException("no IN_PROGRESS idempotency record for key " + key);
        }
    }

    /** SHA-256 of the canonical request, as lowercase hex (64 characters). */
    public static String hash(String canonicalRequest) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private Row lockRow(String scope, String key) {
        List<Row> rows = jdbc.query("""
                SELECT request_hash, status, response_status, response_body, lease_until > CURRENT_TIMESTAMP(6) AS live
                FROM idempotency_record WHERE scope = ? AND idem_key = ? FOR UPDATE
                """, (rs, n) -> new Row(
                rs.getString("request_hash"),
                "DONE".equals(rs.getString("status")),
                (Integer) rs.getObject("response_status"),
                rs.getString("response_body"),
                rs.getBoolean("live")), scope, key);
        if (rows.isEmpty()) {
            throw new IllegalStateException("idempotency record vanished for key " + key);
        }
        return rows.getFirst();
    }

    private record Row(String requestHash, boolean done, Integer responseStatus, String responseBody,
                       boolean leaseLive) {
    }
}
