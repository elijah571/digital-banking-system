package org.elibanking.accountservice.verification;

import org.elibanking.accountservice.entity.Account;
import org.elibanking.accountservice.entity.AccountStatus;
import org.elibanking.accountservice.entity.AccountTier;
import org.elibanking.accountservice.entity.AccountType;
import org.elibanking.accountservice.repository.AccountRepository;
import org.elibanking.accountservice.security.Caller;
import org.elibanking.accountservice.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdentityVerificationServiceTest {

    private static final String IMAGE = Base64.getEncoder().encodeToString(
            new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02});
    private static final String WEBM_VIDEO = webmVideo();

    @Autowired
    private IdentityVerificationService verificationService;

    @Autowired
    private AccountRepository accountRepository;

    private Caller caller;

    @BeforeEach
    void createAccount() {
        String ownerId = "verification-" + UUID.randomUUID();
        caller = new Caller(ownerId, ownerId + "@example.test", "USER");
        Account account = new Account();
        account.setAccountNumber(String.format("%010d", Math.floorMod(UUID.randomUUID().hashCode(), 10_000_000_000L)));
        account.setOwnerId(ownerId);
        account.setAccountHolderName("Elijah Okafor");
        account.setEmail(ownerId + "@example.test");
        account.setPhone("080" + String.format("%08d", Math.floorMod(UUID.randomUUID().hashCode(), 100_000_000)));
        account.setAccountType(AccountType.SAVINGS);
        account.setAccountStatus(AccountStatus.ACTIVE);
        account.setAccountTier(AccountTier.STANDARD);
        account.setBalance(BigDecimal.ZERO);
        account.setDailyTransactionLimit(new BigDecimal("100000.00"));
        accountRepository.saveAndFlush(account);
    }

    @Test
    void documentIdentityVerificationSubmissionWaitsForManualReview() {
        VerificationStatusResponse result = verificationService.verifyIdentity(caller,
                new DocumentIdentityRequest("NIN_SLIP", IMAGE, WEBM_VIDEO, true));

        assertEquals("MANUAL", result.provider());
        assertEquals("PENDING_REVIEW", result.identityStatus());
        assertEquals("NIN_SLIP", result.documentType());
        assertNull(result.identityLivenessScore());
    }

    @Test
    void accountUpgradeSubmissionWaitsForManualReview() {
        VerificationStatusResponse result = verificationService.upgradeAccount(caller,
                new AccountUpgradeRequest("12345678901", "Elijah", "Okafor", "UTILITY_BILL",
                        "25 Main Street Lagos", IMAGE, WEBM_VIDEO, true));

        assertEquals("PENDING_REVIEW", result.accountUpgradeStatus());
        assertEquals("PENDING_REVIEW", result.addressStatus());
        assertEquals(AccountTier.STANDARD, result.accountTier());
        assertEquals(new BigDecimal("100000.00"), result.dailyTransactionLimit());
        assertEquals(AccountTier.STANDARD, accountRepository
                .findAllByOwnerIdOrderByCreatedAtDesc(caller.userId()).getFirst().getAccountTier());
    }

    private static String webmVideo() {
        byte[] bytes = new byte[1024];
        bytes[0] = 0x1a;
        bytes[1] = 0x45;
        bytes[2] = (byte) 0xdf;
        bytes[3] = (byte) 0xa3;
        return Base64.getEncoder().encodeToString(bytes);
    }
}
