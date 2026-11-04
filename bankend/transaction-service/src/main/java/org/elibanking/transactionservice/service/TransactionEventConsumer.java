package org.elibanking.transactionservice.service;

import org.elibanking.transactionservice.config.OtpTopicConfig;
import org.elibanking.transactionservice.exception.TransactionNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.transactionservice.entity.Transaction;
import org.elibanking.transactionservice.entity.TransactionStatus;
import org.elibanking.transactionservice.repository.TransactionRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
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
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final OtpCodes otps;

    public TransactionEventConsumer(TransactionalService transactionalService,
                                    TransactionRepository transactionRepository,
                                    RedisTemplate<String, String> redisTemplate,
                                    KafkaTemplate<String, Object> kafkaTemplate,
                                    OtpCodes otps) {
        this.transactionalService = transactionalService;
        this.transactionRepository = transactionRepository;
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.otps = otps;
    }

    @KafkaListener(topics = "transaction.verification.required", groupId = "transaction-service-group")
    public void consumeVerificationRequired(@Payload Map<String, Object> payload) {
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
            redisTemplate.opsForValue().set(OtpCodes.codeKey(transactionId), otps.hash(transactionId, otp), OtpCodes.TTL);
            redisTemplate.opsForValue().set(OtpCodes.attemptsKey(transactionId), "0", OtpCodes.TTL);

            transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);
            transactionRepository.save(transaction);

            log.info("OTP generated for transaction: {} expires in {} minutes", transactionId, OtpCodes.TTL.toMinutes());

            Map<String, Object> otpEvent = new HashMap<>();
            otpEvent.put("transactionId", transactionId);
            otpEvent.put("accountNumber", accountNumber);
            otpEvent.put("reason", reason);
            otpEvent.put("otp", otp);
            otpEvent.put("amount", payload.get("amount"));

            kafkaTemplate.send(OtpTopicConfig.NOTIFICATION_OTP_TOPIC, transactionId, otpEvent);

        } catch (Exception e) {
            log.error("Error handling verification required: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "fraud.check.clean", groupId = "transaction-service-group")
    public void consumeFraudCheckCleanResult(@Payload Map<String, Object> payload) {
        try {
            String transactionId = (String) payload.get("transactionId");
            transactionalService.processCleanResult(transactionId);
        } catch (Exception e) {
            log.error("Error processing fraud check result: {}", e.getMessage(), e);
        }
    }
}
