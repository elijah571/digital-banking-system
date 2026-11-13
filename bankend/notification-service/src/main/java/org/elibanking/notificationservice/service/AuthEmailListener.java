package org.elibanking.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.common.events.KafkaEventEnvelope;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Emails for the auth flows published by auth-service. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthEmailListener {

    private final CustomerEmailDelivery delivery;

    @KafkaListener(topics = "auth.email-verification.requested", groupId = "notification-service-group")
    public void onVerificationRequested(@Payload Map<String, Object> event) {
        Map<String, Object> payload = KafkaEventEnvelope.payload(event);
        String link = str(payload, "link");
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.emailVerification(
                str(payload, "fullName"), link, hours(str(payload, "expiresInMinutes")));
        delivery.send(event, str(payload, "email"), email);
    }

    @KafkaListener(topics = "auth.password-reset.requested", groupId = "notification-service-group")
    public void onPasswordResetRequested(@Payload Map<String, Object> event) {
        Map<String, Object> payload = KafkaEventEnvelope.payload(event);
        String token = str(payload, "token");
        String link = str(payload, "link");
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.passwordReset(
                str(payload, "fullName"), link, token, hours(str(payload, "expiresInMinutes")));
        delivery.send(event, str(payload, "email"), email);
    }

    @KafkaListener(topics = "auth.password.changed", groupId = "notification-service-group")
    public void onPasswordChanged(@Payload Map<String, Object> event) {
        Map<String, Object> payload = KafkaEventEnvelope.payload(event);
        CustomerEmailTemplates.Email email = CustomerEmailTemplates.passwordChanged(str(payload, "fullName"));
        delivery.send(event, str(payload, "email"), email);
    }

    private static String hours(String minutes) {
        try {
            long m = Long.parseLong(minutes);
            return m % 60 == 0 ? (m / 60) + " hours" : m + " minutes";
        } catch (NumberFormatException e) {
            return minutes + " minutes";
        }
    }

    private static String str(Map<String, Object> event, String key) {
        Object v = event.get(key);
        return v == null ? "" : v.toString();
    }

}
