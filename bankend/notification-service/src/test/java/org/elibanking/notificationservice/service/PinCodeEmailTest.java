package org.elibanking.notificationservice.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** A PIN code is emailed to the user; a transfer code is not (wallet-plan 3.5, D-P4). Plain mocks, no context. */
class PinCodeEmailTest {

    private final EmailSender emailSender = mock(EmailSender.class);
    private final NotificationService notifications = new NotificationService(emailSender);

    @Test
    void aPinCodeIsEmailedToTheUser() {
        notifications.consumeOtp(Map.of("purpose", "PIN_CHANGE", "userId", "alice", "email", "alice@example.test",
                "otp", "483920", "reason", "PIN change"));

        verify(emailSender).send(eq("alice@example.test"), eq("Your EliBanking PIN code"), anyString(),
                contains("Your OTP is 483920"));
    }

    @Test
    void aTransferCodeIsNotEmailed() {
        notifications.consumeOtp(Map.of("transactionId", "tx-1", "accountNumber", "1000000001",
                "otp", "111222", "reason", "large amount", "amount", "250.00"));

        verify(emailSender, never()).send(any(), any(), any(), any());
    }
}
