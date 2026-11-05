package org.elibanking.transactionservice.idempotency;

import org.elibanking.transactionservice.exception.IdempotencyInProgressException;
import org.elibanking.transactionservice.exception.IdempotencyKeyMismatchException;
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

/**
 * Idempotency records for POST /api/v1/transactions (wallet-plan 3.6), the same flow as account-service's:
 * <ol>
 *   <li>{@link #begin}: insert the key as IN_PROGRESS with a lease, in its own short transaction. If the key exists: a
 *       different request hash is a mismatch (422); a DONE record replays its stored response; an IN_PROGRESS record with a live
 *       lease is still in progress (409); an IN_PROGRESS record with an expired lease is taken over.</li>
 *   <li>The transfer runs. {@link #recordTransaction} writes the transfer's id into the record inside the transaction that saves
 *       the transfer, so a takeover knows whether a transfer already exists, and resumes it instead of making another.</li>
 *   <li>{@link #complete}: store the response and mark the record DONE.</li>
 * </ol>
 * The lease is judged on the database clock. The Redis fast path that 3.6 describes is not built; the record is the only store.
 */
@Service
public class IdempotencyService {

    /** A stored response to replay: its status and its JSON body. */
    public record StoredResponse(int status, String body) {
    }

    /**
     * The outcome of {@link #begin}. {@code stored} is set when the request must be answered from the record. Otherwise
     * {@code transactionId} is set when an earlier attempt already saved a transfer, and null when none did.
     */
    public record Begin(StoredResponse stored, String transactionId) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Begin begin(String scope, String key, String requestHash, Duration lease) {
        long leaseMicros = lease.toNanos() / 1000;
        try {
            jdbc.update("INSERT INTO idempotency_record (scope, idem_key, request_hash, status, lease_until)"
                            + " VALUES (?, ?, ?, 'IN_PROGRESS', TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)))",
                    scope, key, requestHash, leaseMicros);
            return new Begin(null, null);
        } catch (DuplicateKeyException existing) {
            Row row = lockRow(scope, key);
            if (!row.requestHash().equals(requestHash)) {
                throw new IdempotencyKeyMismatchException("Idempotency-Key " + key
                        + " was already used for a different request");
            }
            if (row.done()) {
                int status = row.responseStatus() == null ? 0 : row.responseStatus();
                return new Begin(new StoredResponse(status, row.responseBody()), null);
            }
            if (row.leaseLive()) {
                throw new IdempotencyInProgressException("A request with Idempotency-Key " + key
                        + " is still in progress; retry after it has finished");
            }
            // The earlier attempt died holding the key. Take it over. If it had saved a transfer, that transfer is resumed.
            jdbc.update("UPDATE idempotency_record SET lease_until = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6))"
                    + " WHERE scope = ? AND idem_key = ? AND status = 'IN_PROGRESS'", leaseMicros, scope, key);
            return new Begin(null, row.transactionId());
        }
    }

    /** Links the transfer to its key. Must run inside the transaction that saves the transfer, so the two commit together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTransaction(String scope, String key, String transactionId) {
        int updated = jdbc.update("UPDATE idempotency_record SET transaction_id = ?"
                + " WHERE scope = ? AND idem_key = ? AND status = 'IN_PROGRESS'", transactionId, scope, key);
        if (updated != 1) {
            throw new IllegalStateException("no IN_PROGRESS idempotency record for Idempotency-Key " + key);
        }
    }

    /** Stores the response and marks the record DONE. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String scope, String key, int responseStatus, String responseBodyJson) {
        int updated = jdbc.update("UPDATE idempotency_record SET status = 'DONE', response_status = ?, response_body = ?"
                + " WHERE scope = ? AND idem_key = ? AND status = 'IN_PROGRESS'", responseStatus, responseBodyJson, scope, key);
        if (updated != 1) {
            throw new IllegalStateException("no IN_PROGRESS idempotency record for Idempotency-Key " + key);
        }
    }

    /**
     * Removes an IN_PROGRESS record that never saved a transfer, so a retry can start again at once. A record that did save
     * one is kept, and its takeover resumes that transfer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String scope, String key) {
        jdbc.update("DELETE FROM idempotency_record WHERE scope = ? AND idem_key = ?"
                + " AND status = 'IN_PROGRESS' AND transaction_id IS NULL", scope, key);
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
                SELECT request_hash, status, response_status, response_body, transaction_id,
                       lease_until > CURRENT_TIMESTAMP(6) AS live
                FROM idempotency_record WHERE scope = ? AND idem_key = ? FOR UPDATE
                """, (rs, n) -> new Row(
                rs.getString("request_hash"),
                "DONE".equals(rs.getString("status")),
                (Integer) rs.getObject("response_status"),
                rs.getString("response_body"),
                rs.getString("transaction_id"),
                rs.getBoolean("live")), scope, key);
        if (rows.isEmpty()) {
            throw new IllegalStateException("idempotency record vanished for Idempotency-Key " + key);
        }
        return rows.getFirst();
    }

    private record Row(String requestHash, boolean done, Integer responseStatus, String responseBody,
                       String transactionId, boolean leaseLive) {
    }
}
