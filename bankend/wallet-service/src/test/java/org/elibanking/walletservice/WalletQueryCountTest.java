package org.elibanking.walletservice;

import jakarta.persistence.EntityManager;
import org.elibanking.walletservice.deposit.Deposit;
import org.elibanking.walletservice.deposit.DepositRepository;
import org.elibanking.walletservice.deposit.DepositService;
import org.elibanking.walletservice.payout.AdminPayoutController;
import org.elibanking.walletservice.payout.OutboundPayout;
import org.elibanking.walletservice.payout.OutboundPayoutRepository;
import org.elibanking.walletservice.payout.PayoutState;
import org.elibanking.walletservice.payout.PayoutType;
import org.elibanking.walletservice.security.Caller;
import org.elibanking.walletservice.withdrawal.WithdrawalService;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D17: all wallet list/detail reads stay bounded as the result set grows. */
@SpringBootTest(properties = {"outbox.relay.enabled=false", "deposit.expiry.enabled=false"})
@Import(TestcontainersConfiguration.class)
@Transactional
class WalletQueryCountTest {

    @Autowired DepositRepository deposits;
    @Autowired OutboundPayoutRepository payouts;
    @Autowired DepositService depositService;
    @Autowired WithdrawalService withdrawalService;
    @Autowired AdminPayoutController adminPayouts;
    @Autowired SessionFactory sessionFactory;
    @Autowired EntityManager entityManager;

    @Test
    void walletListAndDetailReadsUseOneJpaQuery() {
        String owner = "query-owner-" + UUID.randomUUID();
        String depositId = UUID.randomUUID().toString();
        deposits.saveAndFlush(Deposit.pending(depositId, owner, "9000000001", new BigDecimal("50.00"), "NGN",
                Instant.now().plusSeconds(600)));

        long firstWithdrawal = -1;
        long firstPayout = -1;
        for (int i = 0; i < 25; i++) {
            OutboundPayout payout = payout(owner, i == 0 ? PayoutType.WITHDRAWAL : PayoutType.EXTERNAL_TRANSFER);
            OutboundPayout saved = payouts.saveAndFlush(payout);
            if (i == 0) firstWithdrawal = saved.getId();
            if (i == 1) firstPayout = saved.getId();
        }

        var statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);

        statistics.clear();
        entityManager.clear();
        depositService.view(owner, depositId);
        assertEquals(1, statistics.getPrepareStatementCount(), "deposit detail should use one query");

        Caller user = new Caller(owner, "query-count@example.test", "Query Count", "USER");
        statistics.clear();
        entityManager.clear();
        withdrawalService.view(user, firstWithdrawal);
        assertEquals(1, statistics.getPrepareStatementCount(), "withdrawal detail should use one query");

        statistics.clear();
        entityManager.clear();
        withdrawalService.viewPayout(user, firstPayout);
        assertEquals(1, statistics.getPrepareStatementCount(), "payout detail should use one query");

        statistics.clear();
        entityManager.clear();
        assertTrue(adminPayouts.list(PayoutState.PENDING_APPROVAL, 200).size() >= 25);
        assertEquals(1, statistics.getPrepareStatementCount(), "admin payout queue should use one query");
    }

    private static OutboundPayout payout(String userId, PayoutType type) {
        Instant now = Instant.now();
        OutboundPayout payout = new OutboundPayout();
        payout.setType(type);
        payout.setUserId(userId);
        payout.setIdempotencyKey("query-" + UUID.randomUUID());
        payout.setAccountNumber("9000000001");
        payout.setAmount(new BigDecimal("25.00"));
        payout.setFee(BigDecimal.ZERO);
        payout.setCurrency("NGN");
        payout.setBankCode("058");
        payout.setDestinationAccountNumber("0123456789");
        payout.setDestinationAccountName("Query Count");
        payout.setState(PayoutState.PENDING_APPROVAL);
        payout.setCreatedAt(now);
        payout.setUpdatedAt(now);
        return payout;
    }
}
