package org.elibanking.accountservice.verification;

import lombok.RequiredArgsConstructor;
import org.elibanking.accountservice.exception.AccountNotFoundException;
import org.elibanking.accountservice.repository.AccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class VerificationStateWriter {

    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;

    @Transactional
    public void identityResult(String ownerId, String status, String documentType, BigDecimal faceMatchScore,
                               BigDecimal livenessScore, boolean consentGranted) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO account_verification (
                    owner_id, provider, identity_status, document_type,
                    identity_face_match_score, identity_liveness_score,
                    identity_consent_at, identity_verified_at
                )
                VALUES (?, 'DOJAH', ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    provider = 'DOJAH',
                    identity_status = VALUES(identity_status),
                    document_type = VALUES(document_type),
                    identity_face_match_score = VALUES(identity_face_match_score),
                    identity_liveness_score = VALUES(identity_liveness_score),
                    identity_consent_at = VALUES(identity_consent_at),
                    identity_verified_at = VALUES(identity_verified_at)
                """, ownerId, status, documentType, faceMatchScore, livenessScore, consentGranted ? now : null,
                "VERIFIED".equals(status) ? now : null);
    }

    @Transactional
    public void upgradeResult(String ownerId, String status, String bvnLastFour, String addressDocumentType,
                              String addressStatus, BigDecimal faceMatchScore, BigDecimal livenessScore,
                              boolean consentGranted, BigDecimal verifiedDailyLimit) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if ("VERIFIED".equals(status)) {
            int updated = accounts.upgradeOwnedAccounts(ownerId, verifiedDailyLimit);
            if (updated == 0) {
                throw new AccountNotFoundException("No account found for the authenticated user");
            }
        }

        jdbc.update("""
                INSERT INTO account_verification (
                    owner_id, provider, upgrade_status, bvn_last_four, address_document_type, address_status,
                    upgrade_face_match_score, upgrade_liveness_score, upgrade_consent_at, address_verified_at,
                    account_upgraded_at
                )
                VALUES (?, 'DOJAH', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    provider = 'DOJAH',
                    upgrade_status = VALUES(upgrade_status),
                    bvn_last_four = VALUES(bvn_last_four),
                    address_document_type = VALUES(address_document_type),
                    address_status = VALUES(address_status),
                    upgrade_face_match_score = VALUES(upgrade_face_match_score),
                    upgrade_liveness_score = VALUES(upgrade_liveness_score),
                    upgrade_consent_at = VALUES(upgrade_consent_at),
                    address_verified_at = VALUES(address_verified_at),
                    account_upgraded_at = VALUES(account_upgraded_at)
                """, ownerId, status, bvnLastFour, addressDocumentType, addressStatus, faceMatchScore, livenessScore,
                consentGranted ? now : null, "VERIFIED".equals(addressStatus) ? now : null,
                "VERIFIED".equals(status) ? now : null);
    }
}
