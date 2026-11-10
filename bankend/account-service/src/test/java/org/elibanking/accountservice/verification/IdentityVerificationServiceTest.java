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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdentityVerificationServiceTest {

    private static final String IMAGE = Base64.getEncoder().encodeToString(
            new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02});

    @Autowired
    private IdentityVerificationService verificationService;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoBean
    private DojahIdentityProvider dojah;

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
    void documentIdentityVerificationUsesNoBvnAndRequiresDojahLiveness() {
        when(dojah.verifyDocument(anyString(), anyString()))
                .thenReturn(new DojahIdentityProvider.DocumentResult(true, new BigDecimal("98.5"), "National ID"));
        when(dojah.checkLiveness(anyString()))
                .thenReturn(new DojahIdentityProvider.LivenessResult(true, new BigDecimal("99")));

        VerificationStatusResponse result = verificationService.verifyIdentity(caller,
                new DocumentIdentityRequest("NIN_SLIP", IMAGE, IMAGE, true));

        assertEquals("VERIFIED", result.identityStatus());
        assertEquals("NIN_SLIP", result.documentType());
        assertEquals(new BigDecimal("99.00"), result.identityLivenessScore());
        verify(dojah, never()).verifyBvnSelfie(anyString(), anyString());
    }

    @Test
    void accountUpgradeRequiresBvnAddressFaceMatchAndLiveness() {
        when(dojah.validateBvn(anyString(), anyString(), anyString()))
                .thenReturn(new DojahIdentityProvider.BvnResult(true, new BigDecimal("98")));
        when(dojah.verifyBvnSelfie(anyString(), anyString()))
                .thenReturn(new DojahIdentityProvider.SelfieResult(true, new BigDecimal("99")));
        when(dojah.checkLiveness(anyString()))
                .thenReturn(new DojahIdentityProvider.LivenessResult(true, new BigDecimal("99")));
        when(dojah.readAddressDocument(anyString()))
                .thenReturn(java.util.List.of("Elijah Okafor", "25 Main Street Lagos"));

        VerificationStatusResponse result = verificationService.upgradeAccount(caller,
                new AccountUpgradeRequest("12345678901", "Elijah", "Okafor", "UTILITY_BILL",
                        "25 Main Street Lagos", IMAGE, IMAGE, true));

        assertEquals("VERIFIED", result.accountUpgradeStatus());
        assertEquals("VERIFIED", result.addressStatus());
        assertEquals(AccountTier.VERIFIED, result.accountTier());
        assertEquals(new BigDecimal("5000000.00"), result.dailyTransactionLimit());
        assertEquals(AccountTier.VERIFIED, accountRepository
                .findAllByOwnerIdOrderByCreatedAtDesc(caller.userId()).getFirst().getAccountTier());
    }
}
