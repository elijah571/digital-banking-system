package org.elibanking.authservice.service;

import org.elibanking.authservice.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
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
        verify(kafka).send(eq(AuthEventPublisher.VERIFICATION_TOPIC), eq("user-1"), anyMap());
    }

    @Test
    void outsideATransactionTheEventIsSentAtOnce() {
        publisher.passwordChanged(user());

        verify(kafka).send(eq(AuthEventPublisher.PASSWORD_CHANGED_TOPIC), eq("user-1"), anyMap());
    }

    private static User user() {
        User user = new User();
        user.setId("user-1");
        user.setEmail("a@example.test");
        user.setFullName("A Tester");
        return user;
    }
}
