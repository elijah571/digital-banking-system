package org.elibanking.accountservice.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AccountEventConsumerTest {

    @Test
    void blocksTheAccountFromTheVersionedFraudEventEnvelope() {
        AccountService accounts = mock(AccountService.class);
        AccountEventConsumer consumer = new AccountEventConsumer(accounts);
        Map<String, Object> event = Map.of(
                "eventId", "event-1",
                "type", "fraud.detected",
                "version", 1,
                "payload", Map.of("accountNumber", "0123456789"));

        consumer.consumeFraudDetected(event);

        verify(accounts).blockAccount("0123456789");
    }

    @Test
    void stillAcceptsLegacyFlatFraudEvents() {
        AccountService accounts = mock(AccountService.class);
        AccountEventConsumer consumer = new AccountEventConsumer(accounts);

        consumer.consumeFraudDetected(Map.of("accountNumber", "0123456780"));

        verify(accounts).blockAccount("0123456780");
    }
}
