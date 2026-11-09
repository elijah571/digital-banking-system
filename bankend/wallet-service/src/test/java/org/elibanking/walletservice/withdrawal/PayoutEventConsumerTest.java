package org.elibanking.walletservice.withdrawal;

import org.elibanking.walletservice.outbox.OutboxWriter;
import org.elibanking.walletservice.payout.OutboundPayout;
import org.elibanking.walletservice.payout.OutboundPayoutRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayoutEventConsumerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OutboundPayoutRepository payouts = mock(OutboundPayoutRepository.class);
    private final PayoutProcessor processor = mock(PayoutProcessor.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final PayoutEventConsumer consumer = new PayoutEventConsumer(jdbc, new ObjectMapper(), payouts, processor, outbox);

    @Test
    void providerFailureEventOnlyTriggersAnAuthoritativeStatusCheck() {
        when(payouts.findById(12L)).thenReturn(Optional.of(new OutboundPayout()));

        consumer.receive(event("payment.payout.failed", "evt-1", "out-12"));

        verify(processor).advance(12L);
        verify(processor, never()).reverseIfReversed(any(Long.class));
    }

    @Test
    void duplicateProviderEventDoesNotAskTheProviderAgain() {
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new DuplicateKeyException("duplicate event"));

        consumer.receive(event("payment.payout.success", "evt-1", "out-12"));

        verify(processor, never()).advance(any(Long.class));
        verify(processor, never()).reverseIfReversed(any(Long.class));
        verify(outbox, never()).append(anyString(), anyString(), anyString(), any());
    }

    private static String event(String type, String eventId, String payoutKey) {
        return "{\"type\":\"" + type + "\",\"eventId\":\"" + eventId
                + "\",\"payload\":{\"payoutKey\":\"" + payoutKey + "\"}}";
    }
}
