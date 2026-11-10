package org.elibanking.accountservice.verification;

import org.elibanking.accountservice.entity.AccountTier;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VerificationStatusResponse(
        String provider,
        String identityStatus,
        String accountUpgradeStatus,
        AccountTier accountTier,
        BigDecimal dailyTransactionLimit,
        String maskedBvn,
        String documentType,
        String addressDocumentType,
        String addressStatus,
        BigDecimal identityFaceMatchScore,
        BigDecimal identityLivenessScore,
        BigDecimal upgradeFaceMatchScore,
        BigDecimal upgradeLivenessScore,
        LocalDateTime identityVerifiedAt,
        LocalDateTime addressVerifiedAt,
        LocalDateTime accountUpgradedAt
) {
}
