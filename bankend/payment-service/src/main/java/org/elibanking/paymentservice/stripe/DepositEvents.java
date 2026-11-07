package org.elibanking.paymentservice.stripe;

import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.paymentservice.exception.DepositNotRecordedException;
import org.elibanking.paymentservice.outbox.OutboxWriter;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records a Stripe event for a wallet deposit (wallet-plan 3.5, 3.8). The event goes to the outbox with its Stripe event id, and
 * the id is recorded as processed in the same transaction. A second delivery of the same event finds the id and does nothing.
 * A failure throws, so the webhook answers 500 and Stripe retries it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepositEvents {

    static final String CONSUMER = "stripe-webhook";
    static final String SUCCEEDED_TOPIC = "payment.intent.succeeded";
    static final String FAILED_TOPIC = "payment.intent.failed";

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    @Transactional
    public void record(Event event, PaymentIntent intent, String providerReceipt) {
        String depositId = intent.getMetadata().get("depositId");
        try {
            jdbc.update("INSERT INTO processed_event (consumer, event_id) VALUES (?, ?)", CONSUMER, event.getId());
        } catch (DuplicateKeyException alreadyProcessed) {
            log.info("Stripe event {} was already recorded; nothing to do", event.getId());
            return;
        } catch (DataAccessException e) {
            throw new DepositNotRecordedException(event.getId(), e);
        }

        boolean succeeded = "payment_intent.succeeded".equals(event.getType());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stripeEventId", event.getId());
        body.put("paymentIntentId", intent.getId());
        body.put("depositId", depositId);
        body.put("amountReceived", toNaira(intent.getAmountReceived()));
        body.put("currency", intent.getCurrency().toUpperCase());
        body.put("providerReceipt", providerReceipt);
        if (!succeeded) {
            body.put("failureReason", intent.getLastPaymentError() != null
                    ? intent.getLastPaymentError().getMessage() : "Payment failed via Stripe");
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.getId());
        envelope.put("type", succeeded ? SUCCEEDED_TOPIC : FAILED_TOPIC);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", body);

        try {
            outbox.append(succeeded ? SUCCEEDED_TOPIC : FAILED_TOPIC, depositId, depositId, envelope);
        } catch (RuntimeException e) {
            throw new DepositNotRecordedException(event.getId(), e);
        }
        log.info("Deposit event {} recorded for deposit {}", event.getId(), depositId);
    }

    /** Stripe's minor units (kobo) as naira with two decimals, with no rounding. */
    static String toNaira(long minorUnits) {
        return BigDecimal.valueOf(minorUnits).movePointLeft(2).setScale(2).toPlainString();
    }
}
