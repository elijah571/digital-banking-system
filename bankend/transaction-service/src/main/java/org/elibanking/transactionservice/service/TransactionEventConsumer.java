package org.elibanking.transactionservice.service;

import org.elibanking.common.events.KafkaEventEnvelope;
import org.elibanking.transactionservice.exception.TransactionNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.transactionservice.entity.Transaction;
import org.elibanking.transactionservice.entity.TransactionStatus;
import org.elibanking.transactionservice.repository.TransactionRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class TransactionEventConsumer {

    private final TransactionalService transactionalService;
    private final TransactionRepository transactionRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final OtpCodes otps;

    public TransactionEventConsumer(TransactionalService transactionalService,
                                    TransactionRepository transactionRepository,
                                    RedisTemplate<String, String> redisTemplate,
                                    OtpCodes otps) {
        this.transactionalService = transactionalService;
        this.transactionRepository = transactionRepository;
        this.redisTemplate = redisTemplate;
        this.otps = otps;
    }

    @KafkaListener(topics = "transaction.verification.required", groupId = "transaction-service-group")
    public void consumeVerificationRequired(@Payload Map<String, Object> event) {
        Map<String, Object> payload = KafkaEventEnvelope.payload(event);
        KafkaEventEnvelope.withContext(event, () -> handleVerificationRequired(payload));
    }

    private void handleVerificationRequired(Map<String, Object> payload) {
        try {
            String transactionId = (String) payload.get("transactionId");
            String accountNumber = (String) payload.get("accountNumber");
            String reason = (String) payload.get("reason");

            log.info("Verification required - transaction: {} reason: {}", transactionId, reason);

            Transaction transaction = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));

            if (transaction.getStatus() != TransactionStatus.PROCESSING) {
                log.info("Transaction {} is not in PROCESSING state (Current: {}) - skipping",
                        transactionId, transaction.getStatus());
                return;
            }

            // Only a keyed hash is kept in Redis. The code itself goes once, to notification-service, on its own topic.
            String otp = otps.generate();
            redisTemplate.delete(OtpCodes.decisionKey(transactionId)); // a decision from an earlier round must not block this one
            redisTemplate.opsForValue().set(OtpCodes.codeKey(transactionId), otps.hash(transactionId, otp), OtpCodes.TTL);
            redisTemplate.opsForValue().set(OtpCodes.attemptsKey(transactionId), "0", OtpCodes.TTL);

            log.info("OTP generated for transaction: {} expires in {} minutes", transactionId, OtpCodes.TTL.toMinutes());

            Map<String, Object> otpEvent = new HashMap<>();
            otpEvent.put("transactionId", transactionId);
            otpEvent.put("userId", transaction.getSenderUserId());
            otpEvent.put("accountNumber", accountNumber);
            otpEvent.put("reason", reason);
            otpEvent.put("otp", otp);
            otpEvent.put("amount", payload.get("amount"));

            transactionalService.markPendingVerification(transaction, otpEvent);

        } catch (Exception e) {
            log.error("Error handling verification required: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "fraud.check.clean", groupId = "transaction-service-group")
    public void consumeFraudCheckCleanResult(@Payload Map<String, Object> event) {
        Map<String, Object> payload = KafkaEventEnvelope.payload(event);
        KafkaEventEnvelope.withContext(event, () -> handleFraudCheckCleanResult(payload));
    }

    private void handleFraudCheckCleanResult(Map<String, Object> payload) {
        try {
            String transactionId = (String) payload.get("transactionId");
            transactionalService.processCleanResult(transactionId);
        } catch (Exception e) {
            log.error("Error processing fraud check result: {}", e.getMessage(), e);
        }
    }
}
