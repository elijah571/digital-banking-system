package org.elibanking.walletservice.withdrawal;

import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.AccountCheck;
import org.elibanking.walletservice.client.FraudClient;
import org.elibanking.walletservice.outbox.OutboxWriter;
import org.elibanking.walletservice.security.Caller;
import org.elibanking.walletservice.client.AccountServiceClient.LedgerLine;
import org.elibanking.walletservice.enquiry.NameEnquiry;
import org.elibanking.walletservice.enquiry.NameEnquiryService;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.elibanking.walletservice.idempotency.IdempotencyService;
import org.elibanking.walletservice.idempotency.IdempotencyService.Begin;
import org.elibanking.walletservice.payout.OutboundPayout;
import org.elibanking.walletservice.payout.OutboundPayoutRepository;
import org.elibanking.walletservice.payout.PayoutState;
import org.elibanking.walletservice.payout.PayoutTransitions;
import org.elibanking.walletservice.payout.PayoutType;
import org.elibanking.walletservice.pin.PinService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Requests a withdrawal (wallet-plan 8.3, the steps of 3.5 for a withdrawal). The checks run in this order: the destination is not our own
 * bank, the amount is in range, the key is free, the account is the caller's, ACTIVE and in NGN, the name enquiry is valid, the PIN is
 * right, and the day's usage has room. Then, in one transaction, the usage is counted and the payout is REQUESTED. Then the ledger holds
 * the money under {@code out-{id}-hold}.
 *
 * <p>A ledger that refuses the hold (insufficient funds) fails the payout, gives the usage back, and answers 422. A ledger that cannot be
 * reached leaves the payout REQUESTED, and the retry holds it again. The hold key makes that safe: it is posted once.
 */
@Service
public class WithdrawalService {

    static final String ROUTE = "POST /api/v1/wallet/withdrawals";
    static final String CURRENCY = "NGN";
    static final String PAYOUT_PAYABLE = "PAYOUT_PAYABLE:NGN";
    static final String FEE_REVENUE = "FEE_REVENUE:NGN";
    /** Judged on the database clock. A request that dies holds its key for this long at most. */
    static final Duration LEASE = Duration.ofSeconds(60);

    /** The status and JSON body to send, for a new response or a replay. */
    public record Reply(int status, String body) {
    }

    private final OutboundPayoutRepository payouts;
    private final IdempotencyService idempotency;
    private final AccountServiceClient accounts;
    private final NameEnquiryService enquiries;
    private final PinService pins;
    private final PayoutTransitions transitions;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String ownBankCode;
    private final BigDecimal min;
    private final BigDecimal max;
    private final BigDecimal dailyMax;
    private final BigDecimal fee;
    private final OutboxWriter outbox;
    private final FraudClient fraudClient;
    private final BigDecimal approvalThreshold;

    public WithdrawalService(OutboundPayoutRepository payouts, IdempotencyService idempotency, AccountServiceClient accounts,
                             NameEnquiryService enquiries, PinService pins, PayoutTransitions transitions, TransactionTemplate tx,
                             JdbcTemplate jdbc, ObjectMapper mapper,
                             @Value("${wallet.own-bank.code}") String ownBankCode,
                             @Value("${wallet.withdrawals.min}") BigDecimal min,
                             @Value("${wallet.withdrawals.max}") BigDecimal max,
                             @Value("${wallet.withdrawals.daily-max}") BigDecimal dailyMax,
                             @Value("${wallet.outbound-fee}") BigDecimal fee, OutboxWriter outbox, FraudClient fraudClient,
                             @Value("${wallet.withdrawals.approval-threshold}") BigDecimal approvalThreshold) {
        this.outbox = outbox;
        this.fraudClient = fraudClient;
        this.approvalThreshold = approvalThreshold;
        this.payouts = payouts;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.enquiries = enquiries;
        this.pins = pins;
        this.transitions = transitions;
        this.tx = tx;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ownBankCode = ownBankCode;
        this.min = min;
        this.max = max;
        this.dailyMax = dailyMax;
        this.fee = fee;
    }

    public Reply request(String userId, String key, WithdrawalRequest body) {
        if (ownBankCode.equals(body.bankCode())) {
            throw new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY, "Use a transfer for an account at our bank");
        }
        BigDecimal amount = new BigDecimal(body.amount());
        if (amount.compareTo(min) < 0 || amount.compareTo(max) > 0) {
            throw new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A withdrawal is between " + min.toPlainString() + " and " + max.toPlainString() + " " + CURRENCY);
        }
        String scope = userId + " " + ROUTE;
        String requestHash = IdempotencyService.hash(String.join("|", body.accountNumber(), amount.toPlainString(), body.bankCode(),
                body.destinationAccountNumber(), body.nameEnquiryRef(), body.narration() == null ? "" : body.narration()));
        Begin begin = idempotency.begin(scope, key, requestHash, LEASE);
        if (begin.stored() != null) {
            return new Reply(begin.stored().status(), begin.stored().body());
        }

        String payoutId = begin.resourceId();
        if (payoutId == null) {
            try {
                payoutId = open(scope, key, userId, body, amount);
            } catch (RuntimeException refused) {
                // Nothing was linked to the key, so a retry starts again.
                idempotency.release(scope, key);
                throw refused;
            }
        }
        // The key now names a payout. A failure below ends the lease, so a retry holds this same payout.
        try {
            return hold(scope, key, Long.parseLong(payoutId));
        } catch (RuntimeException failed) {
            idempotency.expireLease(scope, key);
            throw failed;
        }
    }

    /** The caller's withdrawal, with its status. Another user's withdrawal is not found, so its existence is not revealed. */
    public WithdrawalResponse view(String userId, long id) {
        OutboundPayout payout = payouts.findById(id)
                .filter(found -> found.getType() == PayoutType.WITHDRAWAL && found.getUserId().equals(userId))
                .orElseThrow(() -> new RequestRefusedException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
        return respond(payout);
    }

    /** The checks, then the usage and the REQUESTED payout, in one transaction. Returns the new payout's id. */
    private String open(String scope, String key, String userId, WithdrawalRequest body, BigDecimal amount) {
        AccountCheck account = accounts.check(body.accountNumber());
        if (!userId.equals(account.ownerId())) {
            throw new RequestRefusedException(HttpStatus.FORBIDDEN, "This account belongs to another user");
        }
        if (!"ACTIVE".equals(account.status())) {
            throw new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY, "This account is not active");
        }
        if (!CURRENCY.equals(account.currency())) {
            throw new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY, "Withdrawals are only from NGN accounts");
        }
        NameEnquiry enquiry = enquiries.validate(userId, body.nameEnquiryRef(), body.bankCode(), body.destinationAccountNumber());
        requirePin(userId, body.pin());
        BigDecimal limit = dailyMax.min(accounts.dailyLimit(body.accountNumber()));

        LocalDate today = LocalDate.now(PayoutTransitions.BUSINESS_ZONE);
        // The day's row is created in its own short transaction, so the lock below never races an insert of that row (wallet-plan 8.3, step 3).
        retryOnDeadlock(() -> tx.execute(status -> jdbc.update(
                "INSERT IGNORE INTO outbound_daily_usage (account_number, usage_day, total, version) VALUES (?, ?, 0, 0)",
                body.accountNumber(), today)));
        return retryOnDeadlock(() -> tx.execute(status -> {
            String sourceAccount = body.accountNumber();
            // The row is locked, so two requests cannot both pass the check for the day (8.3, step 3).
            BigDecimal used = jdbc.queryForObject("SELECT total FROM outbound_daily_usage WHERE account_number = ? AND usage_day = ?"
                    + " FOR UPDATE", BigDecimal.class, sourceAccount, today);
            if (used.add(amount).compareTo(limit) > 0) {
                throw new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "This withdrawal would go over the daily limit of " + limit.toPlainString() + " " + CURRENCY);
            }
            jdbc.update("UPDATE outbound_daily_usage SET total = total + ?, version = version + 1"
                    + " WHERE account_number = ? AND usage_day = ?", amount, sourceAccount, today);

            OutboundPayout payout = new OutboundPayout();
            payout.setType(PayoutType.WITHDRAWAL);
            payout.setUserId(userId);
            payout.setAccountNumber(sourceAccount);
            payout.setAmount(amount);
            payout.setFee(fee);
            payout.setCurrency(CURRENCY);
            payout.setBankCode(body.bankCode());
            payout.setDestinationAccountNumber(body.destinationAccountNumber());
            payout.setDestinationAccountName(enquiry.getAccountName());
            payout.setNarration(body.narration());
            payout.setState(PayoutState.REQUESTED);
            payout.setCreatedAt(Instant.now());
            payout.setUpdatedAt(payout.getCreatedAt());
            OutboundPayout saved = payouts.save(payout);
            jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, details)"
                            + " VALUES (?, 'PAYOUT_REQUESTED', 'outbound_payout', ?, JSON_OBJECT('amount', ?, 'currency', ?))",
                    userId, String.valueOf(saved.getId()), amount.toPlainString(), CURRENCY);
            idempotency.recordResource(scope, key, String.valueOf(saved.getId()));
            return String.valueOf(saved.getId());
        }));
    }

    /**
     * Runs the work again when the database picks it as the loser of a deadlock. That happens when parallel requests lock the same day's row,
     * and the retry is the right answer, not an error to show the user. Gives up after ten attempts.
     */
    private static <T> T retryOnDeadlock(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (PessimisticLockingFailureException deadlock) {
                if (attempt >= 10) {
                    throw deadlock;
                }
            }
        }
    }

    /** Holds the money on the ledger. A refusal by the ledger fails the payout; any other failure is thrown, and the retry holds again. */
    private Reply hold(String scope, String key, long payoutId) {
        OutboundPayout payout = payouts.findById(payoutId).orElseThrow(() ->
                new IllegalStateException("payout " + payoutId + " is missing for Idempotency-Key " + key));
        Optional<String> refusal = holdOnLedger(payout, key);
        if (refusal.isPresent()) {
            String body = failRequested(payoutId, refusal.get());
            idempotency.complete(scope, key, HttpStatus.UNPROCESSABLE_ENTITY.value(), body);
            return new Reply(HttpStatus.UNPROCESSABLE_ENTITY.value(), body);
        }
        OutboundPayout held = payouts.findById(payoutId).orElseThrow();
        if (held.getState() == PayoutState.REQUESTED) {
            gate(held);
        }
        String body = mapper.writeValueAsString(respond(payouts.findById(payoutId).orElseThrow()));
        idempotency.complete(scope, key, HttpStatus.ACCEPTED.value(), body);
        return new Reply(HttpStatus.ACCEPTED.value(), body);
    }

    /**
     * Posts the hold for a REQUESTED payout under its hold key (wallet-plan 3.4). A refusal by the ledger, which is insufficient funds, is
     * returned as its reason, and nothing was held. Any other failure is thrown, and the payout stays REQUESTED for the next attempt.
     */
    Optional<String> holdOnLedger(OutboundPayout payout, String correlationKey) {
        if (payout.getState() != PayoutState.REQUESTED) {
            return Optional.empty();
        }
        List<LedgerLine> lines = new ArrayList<>();
        lines.add(new LedgerLine(payout.getAccountNumber(), null, "DEBIT", payout.getAmount().add(payout.getFee()).toPlainString(),
                CURRENCY));
        lines.add(new LedgerLine(null, PAYOUT_PAYABLE, "CREDIT", payout.getAmount().toPlainString(), CURRENCY));
        if (payout.getFee().signum() > 0) {
            lines.add(new LedgerLine(null, FEE_REVENUE, "CREDIT", payout.getFee().toPlainString(), CURRENCY));
        }
        try {
            accounts.postEntry("OUTBOUND_HOLD", payout.ledgerKey() + "-hold", String.valueOf(payout.getId()), correlationKey, lines);
            return Optional.empty();
        } catch (RequestRefusedException refused) {
            if (refused.status() != HttpStatus.UNPROCESSABLE_ENTITY) {
                throw refused;
            }
            return Optional.of(refused.getMessage());
        }
    }

    /**
     * The ledger refused the hold, so nothing was held: the payout fails, and the day's usage is given back (wallet-plan 8.3). Returns the
     * problem body for the 422 answer.
     */
    String failRequested(long id, String reason) {
        tx.executeWithoutResult(status -> transitions.move(payouts.findById(id).orElseThrow(), PayoutState.FAILED, reason));
        return mapper.writeValueAsString(Map.of("status", HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "title", "Unprocessable Content", "detail", reason));
    }

    /**
     * Recovery for a payout stuck in REQUESTED (wallet-plan 8.3): the hold is posted again under its own hold key, which the ledger answers
     * with a replay if it was posted before, and then the gate runs.
     */
    public void recoverRequested(long id) {
        OutboundPayout payout = payouts.findById(id).orElseThrow();
        Optional<String> refusal = holdOnLedger(payout, payout.ledgerKey());
        if (refusal.isPresent()) {
            failRequested(id, refusal.get());
            return;
        }
        gate(payouts.findById(id).orElseThrow());
    }

    /** The PIN check (wallet-plan D-P2). A wrong PIN counts toward the lock, and a locked PIN is refused until the lock ends. */
    private void requirePin(String userId, String pin) {
        PinService.Check check = pins.check(userId, pin, userId);
        switch (check.outcome()) {
            case VALID -> {
            }
            case WRONG -> throw new RequestRefusedException(HttpStatus.FORBIDDEN, "Wrong PIN");
            case NOT_SET -> throw new RequestRefusedException(HttpStatus.FORBIDDEN, "Set a PIN first");
            case LOCKED -> throw new RequestRefusedException(HttpStatus.LOCKED,
                    "Too many wrong PINs. Try again in " + check.retryAfterSeconds() + " seconds");
        }
    }

    /**
     * Step 4 of a withdrawal (wallet-plan 8.3): approve on its own only when the amount is below the threshold and the fraud check is
     * clean. A fraud check that cannot answer is not clean, so the payout waits for an admin (fail closed).
     */
    private void gate(OutboundPayout payout) {
        Optional<FraudClient.FraudResult> fraud = fraudClient.check("WITHDRAWAL", payout.getAccountNumber(), payout.getAmount());
        boolean approve = fraud.isPresent() && !fraud.get().fraud() && payout.getAmount().compareTo(approvalThreshold) < 0;
        String reason;
        if (approve) {
            reason = "below the approval threshold, and the fraud check is clean";
        } else if (fraud.isEmpty()) {
            reason = "the fraud check did not answer, so an admin must approve it";
        } else if (fraud.get().fraud()) {
            reason = "the fraud check flagged it: " + fraud.get().reason();
        } else {
            reason = "at or above the approval threshold, so an admin must approve it";
        }
        long id = payout.getId();
        String key = payout.ledgerKey();
        tx.executeWithoutResult(status -> {
            OutboundPayout current = payouts.findById(id).orElseThrow();
            if (current.getState() != PayoutState.REQUESTED) {
                return; // an earlier attempt already gated it
            }
            if (approve) {
                transitions.move(current, PayoutState.APPROVED, reason);
            } else {
                transitions.move(current, PayoutState.PENDING_APPROVAL, reason);
                outbox.append("wallet.payout.approval-required", key, key, event("wallet.payout.approval-required", Map.of(
                        "payoutId", id,
                        "amount", current.getAmount().toPlainString(),
                        "reason", reason)));
            }
        });
    }

    /** An admin approves a payout that is waiting. The requester cannot approve their own (maker-checker, wallet-plan 8.3). */
    public WithdrawalResponse approve(Caller approver, long id) {
        requireAdmin(approver);
        if (withdrawal(id).getUserId().equals(approver.userId())) {
            throw new RequestRefusedException(HttpStatus.FORBIDDEN, "The requester cannot approve their own withdrawal");
        }
        tx.executeWithoutResult(status -> transitions.move(awaitingApproval(id), PayoutState.APPROVED,
                "approved by " + approver.userId()));
        return respond(withdrawal(id));
    }

    /** An admin rejects a payout that is waiting. Its money is released to the user. */
    public WithdrawalResponse reject(Caller approver, long id, String reason) {
        requireAdmin(approver);
        release(awaitingApproval(id));
        tx.executeWithoutResult(status -> transitions.move(awaitingApproval(id), PayoutState.REJECTED,
                "rejected by " + approver.userId() + ": " + reason));
        return respond(withdrawal(id));
    }

    /** The user cancels their own payout while it is waiting. Its money is released. Another user's payout is 404. */
    public WithdrawalResponse cancel(String userId, long id) {
        OutboundPayout payout = payouts.findById(id)
                .filter(found -> found.getType() == PayoutType.WITHDRAWAL && found.getUserId().equals(userId))
                .orElseThrow(() -> new RequestRefusedException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
        release(awaitingApproval(payout.getId()));
        tx.executeWithoutResult(status -> transitions.move(awaitingApproval(id), PayoutState.CANCELLED, "cancelled by the user"));
        return respond(withdrawal(id));
    }

    /**
     * Expires a payout that no admin decided in time, and releases its money (wallet-plan 8.3). Returns false when it is no longer waiting,
     * which is the case when a decision was made first.
     */
    public boolean expire(long id) {
        OutboundPayout payout = payouts.findById(id).orElse(null);
        if (payout == null || payout.getState() != PayoutState.PENDING_APPROVAL) {
            return false;
        }
        release(payout);
        tx.executeWithoutResult(status -> transitions.move(awaitingApproval(id), PayoutState.EXPIRED, "no decision in time"));
        return true;
    }

    /** Releases the hold on the ledger: the amount and the fee go back to the wallet, under {@code out-{id}-release}. */
    void release(OutboundPayout payout) {
        List<LedgerLine> lines = new ArrayList<>();
        lines.add(new LedgerLine(null, PAYOUT_PAYABLE, "DEBIT", payout.getAmount().toPlainString(), CURRENCY));
        if (payout.getFee().signum() > 0) {
            lines.add(new LedgerLine(null, FEE_REVENUE, "DEBIT", payout.getFee().toPlainString(), CURRENCY));
        }
        lines.add(new LedgerLine(payout.getAccountNumber(), null, "CREDIT",
                payout.getAmount().add(payout.getFee()).toPlainString(), CURRENCY));
        accounts.postEntry("OUTBOUND_RELEASE", payout.ledgerKey() + "-release", String.valueOf(payout.getId()),
                "payout-" + payout.getId(), lines);
    }

    private OutboundPayout withdrawal(long id) {
        return payouts.findById(id).filter(found -> found.getType() == PayoutType.WITHDRAWAL)
                .orElseThrow(() -> new RequestRefusedException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
    }

    /** The payout, if it is still waiting for approval. Inside a transaction it is locked against a second decision. */
    private OutboundPayout awaitingApproval(long id) {
        OutboundPayout payout = withdrawal(id);
        if (payout.getState() != PayoutState.PENDING_APPROVAL) {
            throw new RequestRefusedException(HttpStatus.CONFLICT, "This withdrawal is not waiting for approval");
        }
        return payout;
    }

    private static void requireAdmin(Caller caller) {
        if (!caller.isAdmin()) {
            throw new RequestRefusedException(HttpStatus.FORBIDDEN, "Only an admin can decide a withdrawal");
        }
    }

    private static Map<String, Object> event(String type, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("type", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);
        return envelope;
    }

    private WithdrawalResponse respond(OutboundPayout payout) {
        return new WithdrawalResponse(payout.getId(), payout.getState().name(), payout.getAccountNumber(),
                payout.getAmount().toPlainString(), payout.getFee().toPlainString(), payout.getCurrency(), payout.getBankCode(),
                payout.getDestinationAccountNumber(), payout.getDestinationAccountName(), payout.getFailureReason());
    }
}
