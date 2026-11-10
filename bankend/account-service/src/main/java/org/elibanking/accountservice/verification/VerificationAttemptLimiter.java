package org.elibanking.accountservice.verification;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VerificationAttemptLimiter {

    private final JdbcTemplate jdbc;

    @Transactional
    public void claim(String ownerId, String purpose) {
        jdbc.update("""
                INSERT INTO verification_attempt (owner_id, purpose, last_attempt_at)
                VALUES (?, ?, NULL)
                ON DUPLICATE KEY UPDATE owner_id = VALUES(owner_id)
                """, ownerId, purpose);

        int updated = jdbc.update("""
                UPDATE verification_attempt
                SET last_attempt_at = UTC_TIMESTAMP(6)
                WHERE owner_id = ?
                  AND purpose = ?
                  AND (last_attempt_at IS NULL
                       OR last_attempt_at <= DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE))
                """, ownerId, purpose);
        if (updated != 1) {
            throw new VerificationRateLimitedException();
        }
    }
}
