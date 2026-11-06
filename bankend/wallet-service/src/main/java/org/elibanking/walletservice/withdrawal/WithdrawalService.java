package org.elibanking.walletservice.withdrawal;

import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.AccountCheck;
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
import java.util.List;
import java.util.Map;

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

    public WithdrawalService(OutboundPayoutRepository payouts, IdempotencyService idempotency, AccountServiceClient accounts,
                             NameEnquiryService enquiries, PinService pins, PayoutTransitions transitions, TransactionTemplate tx,
                             JdbcTemplate jdbc, ObjectMapper mapper,
                             @Value("${wallet.own-bank.code}") String ownBankCode,
                             @Value("${wallet.withdrawals.min}") BigDecimal min,
                             @Value("${wallet.withdrawals.max}") BigDecimal max,
                             @Value("${wallet.withdrawals.daily-max}") BigDecimal dailyMax,
                             @Value("${wallet.outbound-fee}") BigDecimal fee) {
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

        return tx.execute(status -> {
            String sourceAccount = body.accountNumber();
            LocalDate today = LocalDate.now(PayoutTransitions.BUSINESS_ZONE);
            // The day's row is created if missing, then locked, so two requests cannot both pass the check (8.3, step 3).
            jdbc.update("INSERT IGNORE INTO outbound_daily_usage (account_number, usage_day, total, version) VALUES (?, ?, 0, 0)",
                    sourceAccount, today);
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
        });
    }

    /** Holds the money on the ledger. A refusal by the ledger fails the payout; any other failure is thrown, and the retry holds again. */
    private Reply hold(String scope, String key, long payoutId) {
        OutboundPayout payout = payouts.findById(payoutId).orElseThrow(() ->
                new IllegalStateException("payout " + payoutId + " is missing for Idempotency-Key " + key));
        if (payout.getState() == PayoutState.REQUESTED) {
            List<LedgerLine> lines = new ArrayList<>();
            lines.add(new LedgerLine(payout.getAccountNumber(), null, "DEBIT", payout.getAmount().add(payout.getFee()).toPlainString(),
                    CURRENCY));
            lines.add(new LedgerLine(null, PAYOUT_PAYABLE, "CREDIT", payout.getAmount().toPlainString(), CURRENCY));
            if (payout.getFee().signum() > 0) {
                lines.add(new LedgerLine(null, FEE_REVENUE, "CREDIT", payout.getFee().toPlainString(), CURRENCY));
            }
            try {
                accounts.postEntry("OUTBOUND_HOLD", payout.ledgerKey() + "-hold", String.valueOf(payoutId), key, lines);
            } catch (RequestRefusedException refused) {
                if (refused.status() != HttpStatus.UNPROCESSABLE_ENTITY) {
                    throw refused;
                }
                // The ledger refused the hold, so nothing was held: the payout fails, and the day's usage is given back.
                String reason = refused.getMessage();
                tx.executeWithoutResult(status -> transitions.move(payouts.findById(payoutId).orElseThrow(),
                        PayoutState.FAILED, reason));
                String body = mapper.writeValueAsString(Map.of("status", HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        "title", "Unprocessable Content", "detail", reason));
                idempotency.complete(scope, key, HttpStatus.UNPROCESSABLE_ENTITY.value(), body);
                return new Reply(HttpStatus.UNPROCESSABLE_ENTITY.value(), body);
            }
        }
        String body = mapper.writeValueAsString(respond(payouts.findById(payoutId).orElseThrow()));
        idempotency.complete(scope, key, HttpStatus.ACCEPTED.value(), body);
        return new Reply(HttpStatus.ACCEPTED.value(), body);
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

    private WithdrawalResponse respond(OutboundPayout payout) {
        return new WithdrawalResponse(payout.getId(), payout.getState().name(), payout.getAccountNumber(),
                payout.getAmount().toPlainString(), payout.getFee().toPlainString(), payout.getCurrency(), payout.getBankCode(),
                payout.getDestinationAccountNumber(), payout.getDestinationAccountName(), payout.getFailureReason());
    }
}
