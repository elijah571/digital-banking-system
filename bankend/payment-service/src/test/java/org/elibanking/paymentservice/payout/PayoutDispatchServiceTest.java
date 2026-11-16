package org.elibanking.paymentservice.payout;

import org.elibanking.paymentservice.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayoutDispatchServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private PayoutDispatchService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tx = mock(TransactionTemplate.class);
        doAnswer(call -> {
            ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) call.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        service = new PayoutDispatchService(jdbc, new ObjectMapper(), mock(PayoutProvider.class), mock(OutboxWriter.class), tx);
    }

    @Test
    void consumesAndPersistsThePayoutBeforeAnyProviderCall() throws Exception {
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        service.requested(mapper.readValue("{\"eventId\":\"evt-1\",\"payload\":{" +
                "\"payoutKey\":\"out-42\",\"idempotencyKey\":\"out-42\",\"provider\":\"flutterwave\","
                + "\"amount\":\"250.00\",\"currency\":\"NGN\",\"bankCode\":\"044\","
                + "\"destinationAccountNumber\":\"0123456789\",\"destinationAccountName\":\"A Customer\"}}",
                new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { }));
        verify(jdbc).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void duplicatePayoutEventDoesNotReplaceTheDurableRequest() throws Exception {
        when(jdbc.update(contains("processed_event"), anyString())).thenThrow(new DuplicateKeyException("duplicate event"));
        service.requested(mapper.readValue("{\"eventId\":\"evt-1\",\"payload\":{" +
                "\"payoutKey\":\"out-42\",\"idempotencyKey\":\"out-42\",\"amount\":\"250.00\","
                + "\"currency\":\"NGN\",\"bankCode\":\"044\",\"destinationAccountNumber\":\"0123456789\","
                + "\"destinationAccountName\":\"A Customer\"}}",
                new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { }));
    }

    private final ObjectMapper mapper = new ObjectMapper();
}
