package org.elibanking.walletservice.withdrawal;

import lombok.extern.slf4j.Slf4j;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.LedgerLine;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient.Send;
import org.elibanking.walletservice.payout.OutboundPayout;
import org.elibanking.walletservice.payout.OutboundPayoutRepository;
import org.elibanking.walletservice.payout.PayoutState;
import org.elibanking.walletservice.payout.PayoutTransitions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends the approved payouts, and settles or releases them once the provider answers (wallet-plan 8.3, step 5 and recovery).
 *
 * <ul>
 *   <li>APPROVED: the payout moves to PROCESSING, and is sent under {@code out-{id}}. A send that cannot be reached leaves it PROCESSING,
 *       and the next run sends it again under the same key.</li>
 *   <li>PROCESSING: the provider's status is asked. SUCCESS settles it (DR PAYOUT_PAYABLE, CR the provider's clearing) and completes it.
 *       FAILED or REVERSED releases the money and ends it. PENDING waits. NOT_FOUND sends it again under the same key.</li>
 * </ul>
 * Every ledger call uses a permanent key, so a repeat after a crash moves no money twice.
 */
@Slf4j
@Service
public class PayoutProcessor {

    static final int BATCH = 50;
    /**
     * The clearing account of the simulated provider (wallet-plan D22). account-service seeds it in V8. When Paystack is added,
     * its clearing account is PROVIDER_CLEARING:PAYSTACK:NGN, and this becomes the provider's name.
     */
    static final String CLEARING = "PROVIDER_CLEARING:SIMULATOR:NGN";

    private final OutboundPayoutRepository payouts;
    private final PaymentServiceClient payments;
    private final AccountServiceClient accounts;
    private final PayoutTransitions transitions;
    private final WithdrawalService withdrawals;
    private final TransactionTemplate tx;
    private final Duration recoveryAfter;

    public PayoutProcessor(OutboundPayoutRepository payouts, PaymentServiceClient payments, AccountServiceClient accounts,
                           PayoutTransitions transitions, WithdrawalService withdrawals, TransactionTemplate tx,
                           @Value("${wallet.withdrawals.recovery-after}") Duration recoveryAfter) {
        this.recoveryAfter = recoveryAfter;
        this.payouts = payouts;
        this.payments = payments;
        this.accounts = accounts;
        this.transitions = transitions;
        this.withdrawals = withdrawals;
        this.tx = tx;
    }

    /** Advances the payouts that are waiting to be sent, or for the provider's answer. One that fails is left for the next run. */
    public int processDue() {
        List<Long> ids = payouts.findIdsInStates(List.of(PayoutState.REQUESTED, PayoutState.APPROVED, PayoutState.PROCESSING),
                PageRequest.of(0, BATCH));
        int looked = 0;
        for (long id : ids) {
            try {
                advance(id);
                looked++;
            } catch (RuntimeException notAdvanced) {
                log.warn("Payout {} was not advanced this run: {}", id, notAdvanced.getClass().getSimpleName());
            }
        }
        return looked;
    }

    /** One step for one payout. A payout in any other state is left alone. */
    public void advance(long id) {
        OutboundPayout payout = current(id);
        switch (payout.getState()) {
            case APPROVED -> send(id);
            case REQUESTED -> recoverIfStale(payout);
            case PROCESSING -> followTheProvider(current(id));
            default -> {
            }
        }
    }

    /**
     * A completed payout that the bank has since reversed (wallet-plan 8.3, COMPLETED to REVERSED): the reversal entry, which refunds the
     * fee too (D20). Returns false when the payout is not completed or the provider does not say it was reversed.
     */
    public boolean reverseIfReversed(long id) {
        OutboundPayout payout = current(id);
        if (payout.getState() != PayoutState.COMPLETED || !"REVERSED".equals(payments.payoutStatus(payout.ledgerKey()))) {
            return false;
        }
        List<LedgerLine> lines = new ArrayList<>();
        lines.add(new LedgerLine(null, CLEARING, "DEBIT", payout.getAmount().toPlainString(), WithdrawalService.CURRENCY));
        if (payout.getFee().signum() > 0) {
            lines.add(new LedgerLine(null, WithdrawalService.FEE_REVENUE, "DEBIT", payout.getFee().toPlainString(),
                    WithdrawalService.CURRENCY));
        }
        lines.add(new LedgerLine(payout.getAccountNumber(), null, "CREDIT", payout.getAmount().add(payout.getFee()).toPlainString(),
                WithdrawalService.CURRENCY));
        accounts.postEntry("OUTBOUND_REVERSAL", payout.ledgerKey() + "-reversal", String.valueOf(id), "payout-" + id, lines);
        tx.executeWithoutResult(status -> transitions.move(current(id), PayoutState.REVERSED, "the bank reversed the transfer"));
        return true;
    }

    /**
     * A payout still REQUESTED after the recovery window has lost its hold, or its gate, to a failure (wallet-plan 8.3). A payout that is
     * younger than the window is left alone, because its request may still be running.
     */
    private void recoverIfStale(OutboundPayout payout) {
        if (payout.getUpdatedAt().isBefore(Instant.now().minus(recoveryAfter))) {
            withdrawals.recoverRequested(payout.getId());
        }
    }

    /** APPROVED to PROCESSING, then the send. The move comes first, so a send that is lost is found as PROCESSING. */
    private void send(long id) {
        tx.executeWithoutResult(status -> transitions.move(current(id), PayoutState.PROCESSING, "sent to the provider"));
        sendToProvider(current(id));
    }

    private void sendToProvider(OutboundPayout payout) {
        Send sent = payments.sendPayout(payout.ledgerKey(), payout.getAmount().toPlainString(), payout.getBankCode(),
                payout.getDestinationAccountNumber(), payout.getDestinationAccountName(), payout.getNarration());
        if ("FAILED".equals(sent.status())) {
            endWithRelease(payout.getId(), PayoutState.FAILED, "the provider refused the transfer");
            return;
        }
        long id = payout.getId();
        tx.executeWithoutResult(status -> current(id).setProviderReference(sent.providerRef()));
    }

    private void followTheProvider(OutboundPayout payout) {
        long id = payout.getId();
        switch (payments.payoutStatus(payout.ledgerKey())) {
            case "SUCCESS" -> settle(payout);
            case "FAILED" -> endWithRelease(id, PayoutState.FAILED, "the provider failed the transfer");
            case "REVERSED" -> endWithRelease(id, PayoutState.REVERSED, "the provider reversed the transfer before it settled");
            case "NOT_FOUND" -> sendToProvider(payout);
            default -> {
                // PENDING: the provider is still working on it. The next run asks again.
            }
        }
    }

    /** Settles the money to the provider's clearing account, then completes the payout. The settle key is the only settle. */
    private void settle(OutboundPayout payout) {
        long id = payout.getId();
        List<LedgerLine> lines = List.of(
                new LedgerLine(null, WithdrawalService.PAYOUT_PAYABLE, "DEBIT", payout.getAmount().toPlainString(),
                        WithdrawalService.CURRENCY),
                new LedgerLine(null, CLEARING, "CREDIT", payout.getAmount().toPlainString(),
                        WithdrawalService.CURRENCY));
        accounts.postEntry("OUTBOUND_SETTLE", payout.ledgerKey() + "-settle", String.valueOf(id), "payout-" + id, lines);
        tx.executeWithoutResult(status -> transitions.move(current(id), PayoutState.COMPLETED, "the provider confirmed success"));
    }

    /** Releases the hold, then ends the payout. A release that is repeated is a replay on the ledger. */
    private void endWithRelease(long id, PayoutState to, String reason) {
        withdrawals.release(current(id));
        tx.executeWithoutResult(status -> transitions.move(current(id), to, reason));
    }

    private OutboundPayout current(long id) {
        return payouts.findById(id).orElseThrow(() -> new IllegalStateException("payout " + id + " does not exist"));
    }
}
