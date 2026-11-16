package org.elibanking.notificationservice.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PayoutReviewAlertListenerTest {
    @Test
    void sendsTheManualReviewAlertToConfiguredOperationsMailbox() {
        EmailSender emails = mock(EmailSender.class);
        PayoutReviewAlertListener listener = new PayoutReviewAlertListener(emails, "ops@example.test");
        listener.receive(Map.of("payload", Map.of("payoutKey", "out-8", "providerName", "monnify", "reason", "timeout")));
        verify(emails).send(eq("ops@example.test"), eq("Payout requires manual review"), contains("out-8"), contains("out-8"));
    }

    @Test
    void doesNotSendWhenNoOperationsMailboxIsConfigured() {
        EmailSender emails = mock(EmailSender.class);
        new PayoutReviewAlertListener(emails, "").receive(Map.of("payload", Map.of("payoutKey", "out-8")));
        verify(emails, never()).send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
