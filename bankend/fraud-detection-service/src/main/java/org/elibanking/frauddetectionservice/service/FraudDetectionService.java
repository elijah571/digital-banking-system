package org.elibanking.frauddetectionservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.frauddetectionservice.client.AccountServiceClient;
import org.elibanking.frauddetectionservice.model.FraudCheckResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.elibanking.common.events.KafkaEventEnvelope;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class FraudDetectionService {

    private static final String VERIFICATION_REQUIRED_TOPIC = "transaction.verification.required";
    private static final String FRAUD_CHECK_CLEAN_RESULT_TOPIC = "fraud.check.clean";
    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    @Value("${fraud.max-transactions-per-minute}")
    private int maxTransactionPerMinute;

    @Value("${fraud.suspicious-amount-multiplier}")
    private BigDecimal suspiciousAmountMultiplier;

    @Value("${fraud.max-balance-percentage}")
    private BigDecimal maxBalancePercentage;

    public void checkTransaction(Map<String, Object> payload) {
        String transactionId = (String) payload.get("transactionId");
        String accountNumber = (String) payload.get("senderAccountNumber");
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());

        BigDecimal sendBalance = accountServiceClient.getBalance(accountNumber);
        log.info("Checking transaction: {} account: {} amount: {} balance: {}", transactionId, accountNumber, amount, sendBalance);

        FraudCheckResult result = performFraudChecks(accountNumber, amount, sendBalance);

        if (result.isFraud()) {
            log.info("Suspicious activity detected - account: {} reason: {} - requesting OTP verification", accountNumber, result.getReason());
            Map<String, Object> verificationEvent = new HashMap<>();
            verificationEvent.put("transactionId", transactionId);
            verificationEvent.put("accountNumber", accountNumber);
            verificationEvent.put("amount", amount);
            verificationEvent.put("reason", result.getReason());
            kafkaTemplate.send(KafkaEventEnvelope.record(VERIFICATION_REQUIRED_TOPIC, transactionId, verificationEvent));
        } else {
            log.info("Transaction is clean");
            Map<String, Object> transactionCleanEvent = new HashMap<>();
            transactionCleanEvent.put("transactionId", transactionId);
            transactionCleanEvent.put("isFraud", false);
            transactionCleanEvent.put("reason", null);
            kafkaTemplate.send(KafkaEventEnvelope.record(FRAUD_CHECK_CLEAN_RESULT_TOPIC, transactionId, transactionCleanEvent));
        }
    }

    /**
     * The check for an outbound payout, asked by wallet-service before it sends (wallet-plan 3.5, step 3). It runs the same rules as a
     * transfer, and it counts the same velocity, so a burst of payouts is caught too.
     */
    public FraudCheckResult checkWithdrawal(String accountNumber, BigDecimal amount) {
        BigDecimal balance = accountServiceClient.getBalance(accountNumber);
        FraudCheckResult result = performFraudChecks(accountNumber, amount, balance);
        log.info("Payout fraud check - account: {} amount: {} fraud: {}", accountNumber, amount, result.isFraud());
        return result;
    }

    private FraudCheckResult performFraudChecks(
            String accountNumber,
            BigDecimal amount,
            BigDecimal senderBalance
    ) {
        // Velocity check
        if (isVelocityExceeded(accountNumber)) {
            return new FraudCheckResult(
                    true, "Too many transactions in 60 seconds - Velocity limit exceeded"
            );
        }
        // Amount check
        if (isAmountSuspicious(accountNumber, amount)) {
            return new FraudCheckResult(
                    true, "Unusual Transaction amount - exceeded threshold multiplier"
            );
        }
        // Balance check
        if (senderBalance.compareTo(BigDecimal.ZERO) > 0
                && isBalanceCheckFailed(senderBalance, amount)) {
            return new FraudCheckResult(
                    true, "Transaction exceed allowed percentage of account balance"
            );
        }
        return new FraudCheckResult(false, null);
    }

    private boolean isVelocityExceeded(String accountNumber) {
        String key = "fraud-velocity:" + accountNumber;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, 60, TimeUnit.SECONDS);
        }
        log.info("Velocity check - account: {} count: {}/{}", accountNumber, count, maxTransactionPerMinute);
        return count != null && count > maxTransactionPerMinute;
    }

    private boolean isAmountSuspicious(String accountNumber, BigDecimal amount) {
        String averageKey = "fraud:average_amount:" + accountNumber;
        String averageString = redisTemplate.opsForValue().get(averageKey);

        if (averageString == null) {
            redisTemplate.opsForValue().set(averageKey, amount.toString());
            return false;
        }

        BigDecimal averageAmount = new BigDecimal(averageString);
        BigDecimal threshold = averageAmount.multiply(suspiciousAmountMultiplier);

        BigDecimal newAverage = averageAmount.add(amount)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_EVEN);
        redisTemplate.opsForValue().set(averageKey, newAverage.toString());

        log.info("Amount check - amount: {} threshold: {} suspicious: {}",
                amount, threshold, amount.compareTo(threshold) > 0
        );
        return amount.compareTo(threshold) > 0;
    }

    private boolean isBalanceCheckFailed(BigDecimal senderBalance, BigDecimal amount) {
        // Calculate max allowed based on percentage (e.g., senderBalance * 0.90)
        BigDecimal maxAllowed = senderBalance.multiply(maxBalancePercentage);

        log.info("Balance check - amount: {} maxAllowed: {} suspicious: {}",
                amount, maxAllowed, amount.compareTo(maxAllowed) > 0
        );
        return amount.compareTo(maxAllowed) > 0;
    }
}
