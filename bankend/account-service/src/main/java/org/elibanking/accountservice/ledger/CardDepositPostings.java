package org.elibanking.accountservice.ledger;

import org.elibanking.common.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Ledger entries for a card deposit (wallet-plan 3.4, D26, D27). Every entry goes through
 * {@link LedgerPostingService}; nothing here writes the tables directly.
 *
 * <ul>
 *   <li>{@code dep-{id}-credit}: DR PROVIDER_CLEARING:STRIPE:NGN, CR wallet. The requested amount only.</li>
 *   <li>{@code dep-{id}-suspense}: DR PROVIDER_CLEARING:STRIPE:NGN, CR DEPOSIT_SUSPENSE. Whatever arrived and was not
 *       credited.</li>
 *   <li>{@code dep-{id}-fee}: DR PAYMENT_FEES, CR PROVIDER_CLEARING:STRIPE:NGN. Posted when the balance transaction is
 *       known. It is never part of the credit, so the wallet never waits for fee data.</li>
 * </ul>
 */
@Service
public class CardDepositPostings {

    static final String CLEARING_CODE = "PROVIDER_CLEARING:STRIPE:NGN";
    static final String SUSPENSE_CODE = "DEPOSIT_SUSPENSE:NGN";
    static final String FEES_CODE = "PAYMENT_FEES:NGN";

    private final LedgerPostingService ledger;
    private final JdbcTemplate jdbc;

    public CardDepositPostings(LedgerPostingService ledger, JdbcTemplate jdbc) {
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    /**
     * Credits the wallet with the requested amount and books everything else that arrived to suspense. The two postings
     * share one transaction, so either both happen or neither does.
     *
     * @return the outcome: COMPLETED when the full amount arrived, FAILED on a shortfall
     */
    @Transactional
    public DepositSettlement.Outcome settle(String depositId, long walletLedgerAccountId, DepositSettlement settlement) {
        if (settlement.credit().signum() > 0) {
            ledger.post(PostingRequest.of("DEPOSIT_CREDIT", "dep-" + depositId + "-credit", List.of(
                    new PostingLine(systemAccount(CLEARING_CODE), Side.DEBIT, settlement.credit()),
                    new PostingLine(walletLedgerAccountId, Side.CREDIT, settlement.credit()))));
        }
        if (settlement.suspense().signum() > 0) {
            ledger.post(PostingRequest.of("DEPOSIT_SUSPENSE", "dep-" + depositId + "-suspense", List.of(
                    new PostingLine(systemAccount(CLEARING_CODE), Side.DEBIT, settlement.suspense()),
                    new PostingLine(systemAccount(SUSPENSE_CODE), Side.CREDIT, settlement.suspense()))));
        }
        return settlement.outcome();
    }

    /** Books the Stripe fee for one deposit. Call it when the balance transaction is available, not in the credit path. */
    @Transactional
    public void recordFee(String depositId, Money feeNgn) {
        ledger.post(PostingRequest.of("DEPOSIT_FEE", "dep-" + depositId + "-fee", List.of(
                new PostingLine(systemAccount(FEES_CODE), Side.DEBIT, feeNgn),
                new PostingLine(systemAccount(CLEARING_CODE), Side.CREDIT, feeNgn))));
    }

    private long systemAccount(String code) {
        return jdbc.queryForObject("SELECT id FROM ledger_account WHERE code = ?", Long.class, code);
    }
}
