package org.elibanking.notificationservice.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The owner is emailed when the PIN changes (wallet-plan 3.5, threat model D-P6). */
class PinChangedNoticeTest {

    @Test
    void theOwnerIsEmailedWhenThePinChanges() {
        CustomerEmailDelivery delivery = mock(CustomerEmailDelivery.class);
        Map<String, Object> event = Map.of("eventId", "evt-1", "payload", Map.of("userId", "alice", "email", "alice@example.test"));

        new PinChangedNotice(delivery).consume(event);

        verify(delivery).send(eq(event), eq("alice@example.test"), any());
    }
}
