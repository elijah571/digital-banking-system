package org.elibanking.notificationservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class NotificationService {

    @KafkaListener(topics = "transaction.otp.generated", groupId = "notification-service-group")
    public void consumeOtpGenerated(@Payload Map<String, Object> payload) {
        try {
            String accountNumber = (String) payload.get("accountNumber");
            String otp = (String) payload.get("otp");
            String transactionId = (String) payload.get("transactionId");
            Object amountObj = payload.get("amount");
            String reason = (String) payload.get("reason");

            String message = String.format(
                    "Suspicious activity detected on your account. Reason: %s. " +
                            "A transaction of %s is pending verification. " +
                            "Your OTP is %s. Valid for 5 minutes. " +
                            "If this wasn't you, ignore this message.",
                    reason, amountObj, otp
            );

            sendAlert(accountNumber, "TRANSACTION VERIFICATION REQUIRED", message);

        } catch (Exception e) {
            log.error("Error sending OTP notification: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "transaction.completed", groupId = "notification-service-group")
    public void consumeTransactionCompleted(@Payload Map<String, Object> payload) {
        try {
            String senderAccount = (String) payload.get("senderAccountNumber");
            String receiverAccount = (String) payload.get("receiverAccountNumber");
            Object amount = payload.get("amount");

            // 1. Debit Alert to Sender
            String senderMessage = String.format("%s debited from your account %s", amount, senderAccount);
            sendAlert(senderAccount, "DEBIT ALERT", senderMessage);

            // 2. Credit Alert to Receiver
            String receiverMessage = String.format("%s credited to your account %s", amount, receiverAccount);
            sendAlert(receiverAccount, "CREDIT ALERT", receiverMessage);

        } catch (Exception e) {
            log.error("Error sending transaction completion notification: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "fraud.detected", groupId = "notification-service-group")
    public void consumeFraudDetected(@Payload Map<String, Object> payload) {
        try {
            String accountNumber = (String) payload.get("accountNumber");
            String reason = (String) payload.get("reason");

            String message = String.format(
                    "Your account %s has been blocked for security. " +
                            "Reason: %s. " +
                            "Please contact your bank immediately.",
                    accountNumber, reason
            );

            sendAlert(accountNumber, "SUSPICIOUS ACTIVITY DETECTED", message);

        } catch (Exception e) {
            log.error("Error sending fraud alert message: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "transaction.refunded", groupId = "notification-service-group")
    public void consumeTransactionRefunded(@Payload Map<String, Object> payload) {
        try {
            String accountNumber = (String) payload.get("senderAccountNumber");
            Object amount = payload.get("amount");
            String reason = (String) payload.get("reason");

            String message = String.format(
                    "Your transaction was cancelled and refunded. Amount: %s. Reason: %s",
                    amount, reason
            );

            sendAlert(accountNumber, "TRANSACTION REFUNDED", message);

        } catch (Exception e) {
            log.error("Error sending refund notification: {}", e.getMessage(), e);
        }
    }

    // --- NEW: Payment Service Consumers ---

    @KafkaListener(topics = "payment.completed", groupId = "notification-service-group")
    public void consumePaymentCompleted(@Payload Map<String, Object> payload) {
        try {
            String accountNumber = (String) payload.get("accountNumber");
            Object amount = payload.get("amount");
            String stripePaymentId = (String) payload.get("stripePaymentId");

            String message = String.format(
                    "Your payment of %s has been successfully processed via Stripe. Reference ID: %s",
                    amount, stripePaymentId
            );

            sendAlert(accountNumber, "PAYMENT SUCCESSFUL", message);

        } catch (Exception e) {
            log.error("Error sending payment success notification: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "payment.failed", groupId = "notification-service-group")
    public void consumePaymentFailed(@Payload Map<String, Object> payload) {
        try {
            String accountNumber = (String) payload.get("accountNumber");
            Object amount = payload.get("amount");
            String reason = (String) payload.get("reason");

            String message = String.format(
                    "Your payment of %s failed. Reason: %s",
                    amount, reason
            );

            sendAlert(accountNumber, "PAYMENT FAILED", message);

        } catch (Exception e) {
            log.error("Error sending payment failure notification: {}", e.getMessage(), e);
        }
    }

    private void sendAlert(String accountNumber, String subject, String message) {
        // In production, integrate with JavaMailSender or SMS Gateway here.
        log.info("--------------------------------------------------");
        log.info("ALERT [To Account: {}]", accountNumber);
        log.info("SUBJECT: {}", subject);
        log.info("MESSAGE: {}", message);
        log.info("----------------------------------------------------");
    }
}
