package org.elibanking.authservice.service;

import org.elibanking.authservice.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;

import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A verification email must not go out before the transaction that issued its token commits (wallet-plan 8.27). The commit
 * is simulated here by running the registered synchronizations.
 */
class AuthEventPublisherTest {

    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final AuthEventPublisher publisher = new AuthEventPublisher(kafka);

    @AfterEach
    void clearSynchronizations() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void theVerificationEventWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        publisher.verificationRequested(user(), "http://localhost/verify?token=t", Duration.ofMinutes(15));

        verifyNoInteractions(kafka);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        ProducerRecord<String, Object> record = captureRecord();
        org.junit.jupiter.api.Assertions.assertTrue(isVerificationEnvelope(record));
    }

    @Test
    void outsideATransactionTheEventIsSentAtOnce() {
        publisher.passwordChanged(user());

        ProducerRecord<String, Object> record = captureRecord();
        org.junit.jupiter.api.Assertions.assertNotNull(record);
        org.junit.jupiter.api.Assertions.assertEquals(AuthEventPublisher.PASSWORD_CHANGED_TOPIC, record.topic());
        org.junit.jupiter.api.Assertions.assertEquals("user-1", record.key());
        org.junit.jupiter.api.Assertions.assertTrue(record.value() instanceof Map<?, ?> envelope
                && "auth.password.changed".equals(envelope.get("type")));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProducerRecord<String, Object> captureRecord() {
        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass((Class) ProducerRecord.class);
        verify(kafka).send(captor.capture());
        return captor.getValue();
    }

    private static boolean isVerificationEnvelope(ProducerRecord<String, Object> record) {
        return record != null
                && AuthEventPublisher.VERIFICATION_TOPIC.equals(record.topic())
                && "user-1".equals(record.key())
                && record.value() instanceof Map<?, ?> envelope
                && "auth.email-verification.requested".equals(envelope.get("type"))
                && envelope.containsKey("eventId")
                && envelope.get("payload") instanceof Map<?, ?> payload
                && "a@example.test".equals(payload.get("email"));
    }

    private static User user() {
        User user = new User();
        user.setId("user-1");
        user.setEmail("a@example.test");
        user.setFullName("A Tester");
        return user;
    }
}
