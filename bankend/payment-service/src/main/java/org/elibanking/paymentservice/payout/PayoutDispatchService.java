package org.elibanking.paymentservice.payout;

import org.elibanking.paymentservice.outbox.OutboxWriter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable provider dispatch. The wallet's hold is committed before this service makes a provider request. */
@Service
public class PayoutDispatchService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PayoutProvider provider;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public PayoutDispatchService(JdbcTemplate jdbc, ObjectMapper mapper, PayoutProvider provider,
                                 OutboxWriter outbox, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.provider = provider;
        this.outbox = outbox;
        this.tx = tx;
    }

    @KafkaListener(topics = "payout.requested", groupId = "payment-service-payout-dispatch")
    public void requested(Map<String, Object> rawEvent) {
        JsonNode event = mapper.valueToTree(rawEvent);
        String eventId = event.path("eventId").asText();
        JsonNode p = event.path("payload");
        String key = p.path("payoutKey").asText();
        if (eventId.isBlank() || !key.matches("out-[1-9][0-9]*")
                || !key.equals(p.path("idempotencyKey").asText())) {
            throw new IllegalArgumentException("invalid payout dispatch event");
        }
        BigDecimal amount = new BigDecimal(p.path("amount").asText("0"));
        if (amount.signum() <= 0 || !"NGN".equals(p.path("currency").asText())
                || !p.path("bankCode").asText().matches("[A-Za-z0-9]{1,20}")
                || !p.path("destinationAccountNumber").asText().matches("\\d{10}")
                || p.path("destinationAccountName").asText().isBlank()) {
            throw new IllegalArgumentException("invalid payout dispatch details");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("amount", amount.toPlainString());
        payload.put("currency", p.path("currency").asText());
        payload.put("bankCode", p.path("bankCode").asText());
        payload.put("accountNumber", p.path("destinationAccountNumber").asText());
        payload.put("accountName", p.path("destinationAccountName").asText());
        payload.put("narration", p.path("narration").asText(""));
        payload.put("preferredProvider", p.path("provider").asText("flutterwave"));
        tx.executeWithoutResult(s -> {
            try {
                jdbc.update("INSERT INTO processed_event (consumer,event_id) VALUES ('payment-payout-dispatch',?)", eventId);
            } catch (DuplicateKeyException duplicate) {
                return;
            }
            jdbc.update("INSERT INTO payout_dispatch (payout_key,event_id,request_payload,active_provider,state) VALUES (?,?,?,?,'PENDING_PROVIDER') "
                            + "ON DUPLICATE KEY UPDATE request_payload=IF(state='REQUIRES_MANUAL_REVIEW',VALUES(request_payload),request_payload), "
                            + "event_id=IF(state='REQUIRES_MANUAL_REVIEW',VALUES(event_id),event_id), "
                            + "active_provider=IF(state='REQUIRES_MANUAL_REVIEW',VALUES(active_provider),active_provider), "
                            + "retry_count=IF(state='REQUIRES_MANUAL_REVIEW',0,retry_count), "
                            + "next_retry_at=IF(state='REQUIRES_MANUAL_REVIEW',NULL,next_retry_at), "
                            + "failure_reason=IF(state='REQUIRES_MANUAL_REVIEW',NULL,failure_reason), "
                            + "state=IF(state='REQUIRES_MANUAL_REVIEW','PENDING_PROVIDER',state)",
                    key, eventId, mapper.writeValueAsString(payload), p.path("provider").asText("flutterwave"));
        });
    }

    @Scheduled(fixedDelayString = "${payout.dispatch.delay-ms:1000}")
    public void dispatchDue() {
        List<String> keys = jdbc.query("SELECT payout_key FROM payout_dispatch WHERE state='PENDING_PROVIDER' "
                        + "AND (next_retry_at IS NULL OR next_retry_at<=NOW(6)) ORDER BY created_at LIMIT 25",
                (rs, row) -> rs.getString(1));
        keys.forEach(this::dispatch);
    }

    private void dispatch(String key) {
        int claimed = jdbc.update("UPDATE payout_dispatch SET state='DISPATCHING' WHERE payout_key=? AND state='PENDING_PROVIDER' "
                + "AND (next_retry_at IS NULL OR next_retry_at<=NOW(6))", key);
        if (claimed != 1) return;
        Map<String, Object> row = jdbc.queryForMap("SELECT request_payload,retry_count,active_provider FROM payout_dispatch WHERE payout_key=? AND state='DISPATCHING'", key);
        JsonNode p = mapper.readTree(row.get("request_payload").toString());
        int retries = ((Number) row.get("retry_count")).intValue();
        String preferred = p.path("preferredProvider").asText("flutterwave");
        PayoutProvider.SendResult result;
        try {
            if (provider instanceof ResilientPayoutRouter router) {
                result = router.route(new PayoutProvider.PayoutRequest(key, new BigDecimal(p.path("amount").asText()),
                        p.path("bankCode").asText(), p.path("accountNumber").asText(), p.path("accountName").asText(),
                        p.path("narration").asText()), preferred, selected -> tx.executeWithoutResult(s ->
                        jdbc.update("UPDATE payout_dispatch SET active_provider=? WHERE payout_key=?", selected, key)));
            } else {
                result = provider.send(key, new BigDecimal(p.path("amount").asText()), p.path("bankCode").asText(),
                        p.path("accountNumber").asText(), p.path("accountName").asText(), p.path("narration").asText());
            }
        } catch (ResilientPayoutRouter.AmbiguousPayoutException ambiguous) {
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE payout_dispatch SET state='AMBIGUOUS',failure_reason=?,updated_at=NOW(6) WHERE payout_key=?",
                        safeReason(ambiguous), key);
                emit(key, "payment.payout.ambiguous", ambiguous.providerName(), safeReason(ambiguous));
            });
            return;
        } catch (RuntimeException unavailable) {
            int next = retries + 1;
            tx.executeWithoutResult(s -> {
                if (next >= 5) {
                    jdbc.update("UPDATE payout_dispatch SET state='REQUIRES_MANUAL_REVIEW',retry_count=?,failure_reason=? WHERE payout_key=?",
                            next, safeReason(unavailable), key);
                    emit(key, "payment.payout.review_required", null, safeReason(unavailable));
                    emit(key, "notification.payout.review_required", null, safeReason(unavailable));
                } else {
                    long baseDelay = Math.min(60, 2L << Math.min(next - 1, 5));
                    long delay = Math.min(60, baseDelay + java.util.concurrent.ThreadLocalRandom.current().nextLong(baseDelay / 2 + 1));
                    jdbc.update("UPDATE payout_dispatch SET state='PENDING_PROVIDER',retry_count=?,next_retry_at=DATE_ADD(NOW(6),INTERVAL ? SECOND),failure_reason=? WHERE payout_key=?",
                            next, delay, safeReason(unavailable), key);
                    emit(key, "payment.payout.retrying", null, safeReason(unavailable));
                }
            });
            return;
        }
        PayoutProvider.SendResult sent = result;
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE payout_dispatch SET state=?, provider_reference=?, failure_reason=NULL WHERE payout_key=?",
                    sent.status() == PayoutProvider.SendStatus.FAILED ? "FAILED" : "PROCESSING", sent.providerRef(), key);
            emit(key, sent.status() == PayoutProvider.SendStatus.FAILED ? "payment.payout.failed" : "payment.payout.processing",
                    sent.providerName(), null);
        });
    }

    @Scheduled(fixedDelay = 300000)
    public void reconcileAmbiguous() {
        List<Map<String, Object>> due = jdbc.query("SELECT payout_key,state FROM payout_dispatch WHERE state IN "
                        + "('AMBIGUOUS','PROCESSING','DISPATCHING','RECONCILING') AND updated_at < DATE_SUB(NOW(6),INTERVAL 5 MINUTE) "
                        + "ORDER BY updated_at LIMIT 100", (rs, row) -> Map.of("key", rs.getString(1), "state", rs.getString(2)));
        for (Map<String, Object> payout : due) {
            String key = (String) payout.get("key");
            String previousState = (String) payout.get("state");
            if (jdbc.update("UPDATE payout_dispatch SET state='RECONCILING',updated_at=NOW(6) WHERE payout_key=? AND state=? "
                    + "AND updated_at < DATE_SUB(NOW(6),INTERVAL 5 MINUTE)", key, previousState) == 1) {
                reconcile(key, previousState.equals("RECONCILING") ? "AMBIGUOUS" : previousState);
            }
        }
    }

    private void reconcile(String key, String previousState) {
        Map<String, Object> row = jdbc.queryForMap("SELECT active_provider,request_payload,retry_count FROM payout_dispatch WHERE payout_key=?", key);
        String active = (String) row.get("active_provider");
        int retries = ((Number) row.get("retry_count")).intValue();
        try {
            PayoutProvider.StatusResult result = provider.checkStatus(key, active);
            if (result.status() == PayoutProvider.PayoutStatus.NOT_FOUND) {
                int next = retries + 1;
                tx.executeWithoutResult(s -> {
                    if (next >= 5) {
                        String reason = "provider confirmed the payout was not found after automated retries";
                        jdbc.update("UPDATE payout_dispatch SET state='REQUIRES_MANUAL_REVIEW',retry_count=?,failure_reason=? WHERE payout_key=?",
                                next, reason, key);
                        emit(key, "payment.payout.review_required", active, reason);
                        emit(key, "notification.payout.review_required", active, reason);
                    } else {
                        jdbc.update("UPDATE payout_dispatch SET state='PENDING_PROVIDER',retry_count=?,"
                                + "next_retry_at=DATE_ADD(NOW(6),INTERVAL 2 SECOND),updated_at=NOW(6) WHERE payout_key=? AND state='RECONCILING'",
                                next, key);
                    }
                });
                return;
            }
            String eventType = switch (result.status()) {
                case SUCCESS -> "payment.payout.success";
                case FAILED -> "payment.payout.failed";
                case REVERSED -> "payment.payout.reversed";
                case PENDING, NOT_FOUND -> null;
            };
            if (eventType == null) {
                jdbc.update("UPDATE payout_dispatch SET state=?,updated_at=NOW(6) WHERE payout_key=? AND state='RECONCILING'",
                        previousState, key);
                return;
            }
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE payout_dispatch SET state=?,provider_reference=?,updated_at=NOW(6) WHERE payout_key=?",
                        result.status().name(), result.reference(), key);
                emit(key, eventType, active, null);
            });
        } catch (RuntimeException ignored) {
            // An unavailable status endpoint is still ambiguous; never resend a payout with an unknown provider outcome.
            jdbc.update("UPDATE payout_dispatch SET state='AMBIGUOUS',updated_at=NOW(6) WHERE payout_key=? AND state='RECONCILING'", key);
        }
    }

    private void emit(String key, String topic, String active, String reason) {
        Map<String, Object> dispatch = jdbc.queryForMap("SELECT active_provider,retry_count FROM payout_dispatch WHERE payout_key=?", key);
        String selected = active == null ? (String) dispatch.get("active_provider") : active;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payoutKey", key);
        payload.put("providerName", selected);
        payload.put("retryCount", dispatch.get("retry_count"));
        payload.put("reason", reason);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", java.util.UUID.randomUUID().toString());
        event.put("type", topic);
        event.put("version", 1);
        event.put("occurredAt", Instant.now().toString());
        event.put("payload", payload);
        outbox.append(topic, key, key, event);
    }

    private static String safeReason(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
