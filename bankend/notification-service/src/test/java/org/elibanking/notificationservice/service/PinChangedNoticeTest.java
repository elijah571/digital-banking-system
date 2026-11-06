package org.elibanking.notificationservice.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The owner is emailed when the PIN changes (wallet-plan 3.5, threat model D-P6). Plain mocks, no context. */
class PinChangedNoticeTest {

    @Test
    void theOwnerIsEmailedWhenThePinChanges() {
        EmailSender emailSender = mock(EmailSender.class);

        new PinChangedNotice(emailSender).consume(Map.of("userId", "alice", "email", "alice@example.test"));

        verify(emailSender).send(eq("alice@example.test"), eq("Your EliBanking PIN was changed"), anyString(), anyString());
    }
}
