package org.elibanking.paymentservice.payout;

import org.elibanking.paymentservice.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Flutterwave webhook: verif-hash equality, event mapping and dedupe (wallet-plan 9.6, 9.11). */
class FlutterwaveWebhookServiceTest {

    private static final String SECRET = "whsec_test_123";

    private JdbcTemplate jdbc;
    private OutboxWriter outbox;
    private FlutterwaveWebhookService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        outbox = mock(OutboxWriter.class);
        service = new FlutterwaveWebhookService(jdbc, outbox, new ObjectMapper());
        ReflectionTestUtils.setField(service, "webhookSecret", SECRET);
    }

    @Test
    void aValidSuccessfulTransferIsRecordedToTheSuccessTopic() {
        service.verifySignature(SECRET);
        when(jdbc.update(anyString(), eq(FlutterwaveWebhookService.CONSUMER), anyString())).thenReturn(1);

        service.record(body("transfer.completed", "out-000000000001", "SUCCESSFUL", "8500.00"));

        verify(outbox).append(eq("payment.payout.success"), eq("out-1"), eq("out-1"), any());
    }

    @Test
    void aFailedTransferMapsToTheFailedTopic() {
        when(jdbc.update(anyString(), eq(FlutterwaveWebhookService.CONSUMER), anyString())).thenReturn(1);

        service.record(body("transfer.completed", "out-000000000002", "FAILED", "100.00"));

        verify(outbox).append(eq("payment.payout.failed"), eq("out-2"), eq("out-2"), any());
    }

    @Test
    void aReversedTransferMapsToTheReversedTopic() {
        when(jdbc.update(anyString(), eq(FlutterwaveWebhookService.CONSUMER), anyString())).thenReturn(1);

        service.record(body("transfer.completed", "out-000000000003", "REVERSED", "100.00"));

        verify(outbox).append(eq("payment.payout.reversed"), eq("out-3"), eq("out-3"), any());
    }

    @Test
    void rejectsAnInvalidVerifHash() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.verifySignature("wrong")).getStatusCode());
    }

    @Test
    void rejectsAMissingVerifHash() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.verifySignature(null)).getStatusCode());
    }

    @Test
    void anUnconfiguredWebhookIsUnavailable() {
        ReflectionTestUtils.setField(service, "webhookSecret", "");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class, () -> service.verifySignature(SECRET)).getStatusCode());
    }

    @Test
    void aDuplicateEventIsRecordedOnlyOnce() {
        when(jdbc.update(anyString(), eq(FlutterwaveWebhookService.CONSUMER), anyString()))
                .thenThrow(new DuplicateKeyException("duplicate processed_event"));

        service.record(body("transfer.completed", "out-000000000001", "SUCCESSFUL", "8500.00"));

        verifyNoInteractions(outbox);
    }

    @Test
    void aReferenceOutsideThePayoutNamespaceIsIgnored() {
        service.record(body("transfer.completed", "tx-000000000001", "SUCCESSFUL", "100.00"));

        verifyNoInteractions(outbox);
        verifyNoInteractions(jdbc);
    }

    @Test
    void anUnrelatedEventIsIgnored() {
        service.record(body("charge.completed", "out-000000000001", "SUCCESSFUL", "100.00"));

        verifyNoInteractions(outbox);
        verifyNoInteractions(jdbc);
    }

    private static String body(String event, String reference, String status, String amount) {
        return "{\"event\":\"" + event + "\",\"data\":{\"id\":28235,\"reference\":\"" + reference
                + "\",\"amount\":" + amount + ",\"currency\":\"NGN\",\"status\":\"" + status + "\"}}";
    }
}
