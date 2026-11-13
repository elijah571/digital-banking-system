package org.elibanking.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.common.events.KafkaEventEnvelope;
import org.elibanking.notificationservice.client.AuthRecipientClient;
import org.elibanking.notificationservice.client.AuthRecipientClient.Recipient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Branded customer mail for authentication, transfers, deposits, payouts and security events. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final CustomerEmailDelivery delivery;
    private final AuthRecipientClient recipients;

    @KafkaListener(topics = {"transaction.otp.generated", "notification.otp"}, groupId = "notification-service-group")
    public void consumeOtp(@Payload Map<String, Object> event) {
        Map<String, Object> payload = KafkaEventEnvelope.payload(event);
        String purpose = "PIN_CHANGE".equals(payload.get("purpose")) ? "change your transfer PIN" : "verify your transfer";
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.otp(
                fullName(event, payload), value(payload, "otp"), purpose, "5 minutes");
        send(event, payload, email);
    }

    @KafkaListener(topics = "transaction.completed", groupId = "notification-service-group")
    public void consumeTransactionCompleted(@Payload Map<String, Object> event) {
        Map<String, Object> p = KafkaEventEnvelope.payload(event);
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.transfer("successful", value(p, "amount"),
                value(p, "currency", "NGN"), value(p, "senderAccountNumber"),
                value(p, "receiverAccountNumber"), value(p, "transactionId"), "Your transfer was completed.");
        send(event, p, email);
    }

    @KafkaListener(topics = "transaction.refunded", groupId = "notification-service-group")
    public void consumeTransactionRefunded(@Payload Map<String, Object> event) {
        Map<String, Object> p = KafkaEventEnvelope.payload(event);
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.transfer("refunded", value(p, "amount"),
                value(p, "currency", "NGN"), value(p, "senderAccountNumber"), "", value(p, "transactionId"), value(p, "reason"));
        send(event, p, email);
    }

    @KafkaListener(topics = "fraud.detected", groupId = "notification-service-group")
    public void consumeFraudDetected(@Payload Map<String, Object> event) {
        Map<String, Object> p = KafkaEventEnvelope.payload(event);
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.fraudAlert(value(p, "accountNumber"),
                value(p, "amount"), value(p, "transactionId"), value(p, "reason"));
        send(event, p, email);
    }

    @KafkaListener(topics = "payment.completed", groupId = "notification-service-group")
    public void consumePaymentCompleted(@Payload Map<String, Object> event) {
        payment(event, true);
    }

    @KafkaListener(topics = "payment.failed", groupId = "notification-service-group")
    public void consumePaymentFailed(@Payload Map<String, Object> event) {
        payment(event, false);
    }

    @KafkaListener(topics = "wallet.deposit.completed", groupId = "notification-service-group")
    public void consumeDepositCompleted(@Payload Map<String, Object> event) {
        deposit(event, true);
    }

    @KafkaListener(topics = {"wallet.deposit.failed", "wallet.deposit.alert"}, groupId = "notification-service-group")
    public void consumeDepositFailed(@Payload Map<String, Object> event) {
        deposit(event, false);
    }

    @KafkaListener(topics = {
            "wallet.payout.requested", "wallet.payout.pending_approval", "wallet.payout.approved",
            "wallet.payout.processing", "wallet.payout.completed", "wallet.payout.failed",
            "wallet.payout.rejected", "wallet.payout.cancelled", "wallet.payout.expired", "wallet.payout.reversed"
    }, groupId = "notification-service-group")
    public void consumePayoutUpdate(@Payload Map<String, Object> event) {
        Map<String, Object> p = KafkaEventEnvelope.payload(event);
        String state = value(p, "to", value(event, "type", "updated").replace("wallet.payout.", ""));
        String status = state.toLowerCase().replace('_', ' ');
        CustomerEmailTemplates.Email email;
        if ("TRANSFER".equals(value(p, "type"))) {
            email = CustomerEmailTemplates.transfer(status, value(p, "amount"), value(p, "currency", "NGN"),
                    value(p, "accountNumber"), value(p, "destinationAccountNumber"), value(p, "payoutId"), value(p, "reason"));
        } else {
            email = CustomerEmailTemplates.withdrawal(status, value(p, "amount"),
                    value(p, "currency", "NGN"), value(p, "accountNumber"),
                    value(p, "destinationAccountName", value(p, "destinationAccountNumber")),
                    value(p, "payoutId"), value(p, "reason"));
        }
        send(event, p, email);
    }

    private void payment(Map<String, Object> event, boolean successful) {
        Map<String, Object> p = KafkaEventEnvelope.payload(event);
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.payment(successful ? "successful" : "failed",
                value(p, "amount"), value(p, "currency", "NGN"),
                value(p, "stripePaymentId", value(p, "reference")), value(p, "reason"));
        send(event, p, email);
    }

    private void deposit(Map<String, Object> event, boolean successful) {
        Map<String, Object> p = KafkaEventEnvelope.payload(event);
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.deposit(successful ? "successful" : "failed",
                value(p, "amount"), value(p, "currency", "NGN"), value(p, "accountNumber"),
                value(p, "depositId"), value(p, "reason"));
        send(event, p, email);
    }

    private void send(Map<String, Object> event, Map<String, Object> payload, CustomerEmailTemplates.Email email) {
        String emailAddress = value(payload, "email");
        String userId = value(payload, "userId");
        if (emailAddress.isBlank() && !userId.isBlank()) {
            Recipient recipient = recipients.get(userId);
            if (recipient != null) emailAddress = recipient.email();
        }
        if (emailAddress.isBlank()) {
            log.warn("Customer email skipped: no recipient identity for event {}", value(event, "eventId", "legacy"));
            return;
        }
        delivery.send(event, emailAddress, email);
    }

    private String fullName(Map<String, Object> event, Map<String, Object> payload) {
        String name = value(payload, "fullName", value(event, "fullName"));
        if (!name.isBlank()) return name;
        String userId = value(payload, "userId");
        if (userId.isBlank()) return "there";
        Recipient recipient = recipients.get(userId);
        return recipient == null || recipient.fullName() == null ? "there" : recipient.fullName();
    }

    private static String value(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private static String value(Map<String, Object> values, String key, String fallback) {
        String value = value(values, key);
        return value.isBlank() ? fallback : value;
    }
}
