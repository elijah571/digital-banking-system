package org.elibanking.walletservice.deposit;

import lombok.extern.slf4j.Slf4j;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.LedgerLine;
import org.elibanking.walletservice.outbox.OutboxWriter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies payment-service's Stripe outcome for a card deposit (wallet-plan 3.5 steps 4 and 5, D26).
 * <ul>
 *   <li>Each Stripe event id is applied once. It is claimed in processed_event in the same transaction as the work.</li>
 *   <li>A success for a PENDING deposit credits exactly the requested amount. Anything above it goes to suspense. A shortfall
 *       credits nothing, sends the whole receipt to suspense, and fails the deposit.</li>
 *   <li>A failure fails the deposit, with no ledger entry.</li>
 *   <li>A receipt that cannot be credited is never left off the ledger. An unknown deposit, or one that is no longer PENDING, sends
 *       the receipt to suspense under a key of its own. A receipt in another currency is not posted, and the deposit fails.
 *       Each case raises an alert.</li>
 * </ul>
 * The ledger keys are permanent and deterministic. So a retry after a failure replays what already happened, and no money moves
 * twice.
 */
@Slf4j
@Service
public class DepositSettlementService {

    static final String CONSUMER = "wallet-deposit";
    static final String SUCCEEDED = "payment.intent.succeeded";
    static final String FAILED = "payment.intent.failed";
    public static final String COMPLETED_TOPIC = "wallet.deposit.completed";
    public static final String FAILED_TOPIC = "wallet.deposit.failed";
    public static final String ALERT_TOPIC = "wallet.deposit.alert";
    /** The ledger's system accounts for card deposits (CardDepositPostings in account-service). */
    static final String CLEARING = "PROVIDER_CLEARING:STRIPE:NGN";
    static final String SUSPENSE = "DEPOSIT_SUSPENSE:NGN";
    private static final int REASON_MAX = 255;

    private final DepositRepository deposits;
    private final AccountServiceClient accounts;
    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DepositSettlementService(DepositRepository deposits, AccountServiceClient accounts, OutboxWriter outbox,
                                    JdbcTemplate jdbc, ObjectMapper mapper) {
        this.deposits = deposits;
        this.accounts = accounts;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Applies one payment-service event. Any failure is rethrown, so the record is retried and the whole change rolls back. */
    @Transactional
    public void apply(String message) {
        JsonNode event = mapper.readTree(message);
        String type = event.path("type").asText();
        String eventId = event.path("eventId").asText();
        JsonNode body = event.path("payload");
        String depositId = body.path("depositId").asText();
        String stripeEventId = body.path("stripeEventId").asText();
        if (eventId.isEmpty() || depositId.isEmpty()) {
            throw new IllegalArgumentException("payment event without an event id or a deposit id");
        }
        if (!claim(eventId)) {
            log.info("Stripe event {} was already applied; nothing to do", stripeEventId);
            return;
        }
        BigDecimal received = new BigDecimal(body.path("amountReceived").asText("0.00"));
        String currency = body.path("currency").asText();
        Deposit deposit = deposits.findById(depositId).orElse(null);

        if (deposit == null) {
            if (SUCCEEDED.equals(type)) {
                postSuspense(lateKey(depositId, eventId), depositId, received, stripeEventId);
                alert(depositId, "a success for an unknown deposit: the receipt went to suspense", received, stripeEventId);
            }
            return;
        }
        if (deposit.getStatus() != DepositStatus.PENDING) {
            if (SUCCEEDED.equals(type)) {
                postSuspense(lateKey(depositId, eventId), depositId, received, stripeEventId);
                alert(depositId, "a success for a deposit that is " + deposit.getStatus()
                        + ": the receipt went to suspense", received, stripeEventId);
            } else {
                log.info("A failure for deposit {}, which is {}; nothing to do", depositId, deposit.getStatus());
            }
            return;
        }
        if (FAILED.equals(type)) {
            fail(deposit, body.path("failureReason").asText("Payment failed"), stripeEventId);
            return;
        }
        if (!DepositService.CURRENCY.equals(currency) || !deposit.getCurrency().equals(currency)) {
            String reason = "the receipt is in " + currency + ", not " + deposit.getCurrency() + ": nothing was credited";
            fail(deposit, reason, stripeEventId);
            alert(depositId, reason, received, stripeEventId);
            return;
        }
        settle(deposit, stripeEventId, received);
    }

    /** A success for a PENDING deposit: credit the requested amount, book any surplus, and complete the deposit. */
    private void settle(Deposit deposit, String stripeEventId, BigDecimal received) {
        String id = deposit.getId();
        BigDecimal requested = deposit.getAmount();
        if (received.compareTo(requested) < 0) {
            postSuspense("dep-" + id + "-suspense", id, received, stripeEventId);
            fail(deposit, "the amount received, " + received.toPlainString() + ", is less than the "
                    + requested.toPlainString() + " requested", stripeEventId);
            alert(id, "a shortfall: nothing was credited, and the receipt went to suspense", received, stripeEventId);
            return;
        }
        postEntry("DEPOSIT_CREDIT", "dep-" + id + "-credit", id, stripeEventId, List.of(
                systemLine(CLEARING, "DEBIT", requested),
                walletLine(deposit.getAccountNumber(), "CREDIT", requested)));
        BigDecimal surplus = received.subtract(requested);
        if (surplus.signum() > 0) {
            postSuspense("dep-" + id + "-suspense", id, surplus, stripeEventId);
            alert(id, "a surplus of " + surplus.toPlainString() + " went to suspense", surplus, stripeEventId);
        }
        deposit.setStatus(DepositStatus.COMPLETED);
        audit(id, "DEPOSIT_COMPLETED", "credited " + requested.toPlainString());
        outbox.append(COMPLETED_TOPIC, id, id, envelope(COMPLETED_TOPIC, Map.of(
                "depositId", id,
                "amount", requested.toPlainString(),
                "currency", DepositService.CURRENCY,
                "received", received.toPlainString(),
                "stripeEventId", stripeEventId)));
    }

    private void fail(Deposit deposit, String reason, String stripeEventId) {
        String id = deposit.getId();
        String trimmed = reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) : reason;
        deposit.setStatus(DepositStatus.FAILED);
        deposit.setFailureReason(trimmed);
        audit(id, "DEPOSIT_FAILED", trimmed);
        outbox.append(FAILED_TOPIC, id, id, envelope(FAILED_TOPIC, Map.of(
                "depositId", id,
                "reason", trimmed,
                "stripeEventId", stripeEventId)));
    }

    /** Raises an alert for a person to act on (D26): an audit row and an outbox event, in the same transaction. */
    private void alert(String depositId, String reason, BigDecimal amount, String stripeEventId) {
        audit(depositId, "DEPOSIT_ALERT", reason);
        outbox.append(ALERT_TOPIC, depositId, depositId, envelope(ALERT_TOPIC, Map.of(
                "depositId", depositId,
                "reason", reason,
                "amount", amount.toPlainString(),
                "stripeEventId", stripeEventId)));
    }

    private void postSuspense(String key, String depositId, BigDecimal amount, String stripeEventId) {
        if (amount.signum() <= 0) {
            return;
        }
        postEntry("DEPOSIT_SUSPENSE", key, depositId, stripeEventId, List.of(
                systemLine(CLEARING, "DEBIT", amount),
                systemLine(SUSPENSE, "CREDIT", amount)));
    }

    private void postEntry(String type, String key, String reference, String correlationId, List<LedgerLine> lines) {
        accounts.postEntry(type, key, reference, correlationId, lines);
    }

    /** Claims the event id. A second delivery finds it claimed, and returns false. */
    private boolean claim(String eventId) {
        try {
            jdbc.update("INSERT INTO processed_event (consumer, event_id) VALUES (?, ?)", CONSUMER, eventId);
            return true;
        } catch (DuplicateKeyException alreadyApplied) {
            return false;
        }
    }

    private void audit(String depositId, String action, String detail) {
        jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, details)"
                + " VALUES ('wallet-service', ?, 'deposit', ?, JSON_OBJECT('detail', ?))", action, depositId, detail);
    }

    private static String lateKey(String depositId, String eventId) {
        return "dep-" + depositId + "-suspense-" + eventId;
    }

    private static LedgerLine systemLine(String code, String side, BigDecimal amount) {
        return new LedgerLine(null, code, side, amount.toPlainString(), DepositService.CURRENCY);
    }

    private static LedgerLine walletLine(String accountNumber, String side, BigDecimal amount) {
        return new LedgerLine(accountNumber, null, side, amount.toPlainString(), DepositService.CURRENCY);
    }

    /** The envelope every wallet event uses, as payment-service's events do. */
    private static Map<String, Object> envelope(String type, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("type", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);
        return envelope;
    }
}
