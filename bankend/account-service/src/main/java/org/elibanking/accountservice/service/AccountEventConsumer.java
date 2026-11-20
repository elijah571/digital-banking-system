package org.elibanking.accountservice.service;

import org.elibanking.common.events.KafkaEventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final AccountService accountService;

    /**
     * Consume fraud.detected event from Kafka.
     * Blocks the flagged account.
     * @param payload the message payload
     */
    @KafkaListener(topics = "fraud.detected", groupId = "account-service-group")
    public void consumeFraudDetected(@Payload Map<String, Object> payload) {
        try {
            Map<String, Object> eventPayload = KafkaEventEnvelope.payload(payload);
            Object value = eventPayload.get("accountNumber");
            if (!(value instanceof String accountNumber) || accountNumber.isBlank()) {
                throw new IllegalArgumentException("fraud.detected event has no accountNumber");
            }

            log.info("Fraud detected - blocking account: {}", accountNumber);
            accountService.blockAccount(accountNumber);

        } catch (Exception e) {
            log.error("Error blocking account: {}", e.getMessage(), e);
        }
    }
}
