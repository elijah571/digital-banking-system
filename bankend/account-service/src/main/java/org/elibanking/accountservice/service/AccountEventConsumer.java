package org.elibanking.accountservice.service;

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
     * Consume transaction.completed event from Kafka.
     * Credits receiver account.
     * @param payload the message payload
     */
    @KafkaListener(topics = "transaction.completed", groupId = "account-service-group")
    public void consumeTransactionCompleted(@Payload Map<String, Object> payload) {
        try {
            String receiverAccount = (String) payload.get("receiverAccountNumber");
            BigDecimal amount = new BigDecimal(payload.get("amount").toString());

            log.info("Crediting account: {} with amount: {}", receiverAccount, amount);
            accountService.creditBalance(receiverAccount, amount);

        } catch (Exception e) {
            log.error("Error crediting account: {}", e.getMessage(), e);
        }
    }

    /**
     * Consume fraud.detected event from Kafka.
     * Blocks the flagged account.
     * @param payload the message payload
     */
    @KafkaListener(topics = "fraud.detected", groupId = "account-service-group")
    public void consumeFraudDetected(@Payload Map<String, Object> payload) {
        try {

            String accountNumber = (String) payload.get("accountNumber");

            log.info("Fraud detected - blocking account: {}", accountNumber);
            accountService.blockAccount(accountNumber);

        } catch (Exception e) {
            log.error("Error blocking account: {}", e.getMessage(), e);
        }
    }
}
