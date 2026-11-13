package org.elibanking.authservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.authservice.entity.User;
import org.elibanking.common.events.KafkaEventEnvelope;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Publishes email requests; notification-service sends them through SMTP. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthEventPublisher {

    public static final String VERIFICATION_TOPIC = "auth.email-verification.requested";
    public static final String PASSWORD_RESET_TOPIC = "auth.password-reset.requested";
    public static final String PASSWORD_CHANGED_TOPIC = "auth.password.changed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void verificationRequested(User user, String link, Duration ttl) {
        send(VERIFICATION_TOPIC, user, Map.of("link", link, "expiresInMinutes", ttl.toMinutes()));
    }

    public void passwordResetRequested(User user, String token, String link, Duration ttl) {
        send(PASSWORD_RESET_TOPIC, user, Map.of("token", token, "link", link, "expiresInMinutes", ttl.toMinutes()));
    }

    public void passwordChanged(User user) {
        send(PASSWORD_CHANGED_TOPIC, user, Map.of());
    }

    private void send(String topic, User user, Map<String, Object> extra) {
        Map<String, Object> event = new HashMap<>(extra);
        event.put("userId", user.getId());
        event.put("email", user.getEmail());
        event.put("fullName", user.getFullName());
        // Publish only after the business transaction commits. A verification link is emailed as soon as the event is consumed,
        // and the link works only once its token is committed. Sending earlier let a fast click arrive first and get 400.
        Runnable publish = () -> {
            kafkaTemplate.send(KafkaEventEnvelope.record(topic, user.getId(), event));
            log.info("Published {} for user {}", topic, user.getId());
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
