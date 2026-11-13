package org.elibanking.frauddetectionservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.common.events.KafkaEventEnvelope;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Service
public class FraudDetectionEventConsumer {

    private final FraudDetectionService fraudDetectionService;

    /**
     * Listens to transaction.initiated
     *
     */
    @KafkaListener(topics = "transaction.initiated", groupId = "fraud-detection-group")
    public void consumeTransactionInitiated(@Payload Map<String, Object> payload) {
        Map<String, Object> body = KafkaEventEnvelope.payload(payload);
        log.info("Received transaction for fraud check: {}", body.get("transactionId"));
        KafkaEventEnvelope.withContext(payload, () -> {
            try {
                fraudDetectionService.checkTransaction(body);
            } catch (Exception e) {
                log.error("Error processing transaction for fraud check: {}", body.get("transactionId"), e);
            }
        });
    }
}
