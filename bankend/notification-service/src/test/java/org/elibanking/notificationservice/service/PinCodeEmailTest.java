package org.elibanking.notificationservice.service;

import org.elibanking.notificationservice.client.AuthRecipientClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PIN and transfer verification codes use the same branded, private email channel. */
class PinCodeEmailTest {

    private final CustomerEmailDelivery delivery = mock(CustomerEmailDelivery.class);
    private final AuthRecipientClient recipients = mock(AuthRecipientClient.class);
    private final NotificationService notifications = new NotificationService(delivery, recipients);

    @Test
    void aPinCodeIsEmailedToTheAddressInThePinRequest() {
        Map<String, Object> event = Map.of("purpose", "PIN_CHANGE", "userId", "alice", "email", "alice@example.test",
                "otp", "483920", "reason", "PIN change");
        notifications.consumeOtp(event);

        verify(delivery).send(eq(event), eq("alice@example.test"), any());
    }

    @Test
    void aTransferCodeIsEmailedToTheVerifiedAccountOwner() {
        Map<String, Object> event = Map.of("eventId", "evt-1", "payload", Map.of("transactionId", "tx-1",
                "userId", "alice", "accountNumber", "1000000001", "otp", "111222", "reason", "large amount", "amount", "250.00"));
        when(recipients.get("alice")).thenReturn(new AuthRecipientClient.Recipient("alice@example.test", "Alice"));

        notifications.consumeOtp(event);

        var email = org.mockito.ArgumentCaptor.forClass(CustomerEmailTemplates.Email.class);
        verify(delivery).send(eq(event), eq("alice@example.test"), email.capture());
        assertTrue(email.getValue().plainText().contains("verify your transfer: 111222"));
    }
}
