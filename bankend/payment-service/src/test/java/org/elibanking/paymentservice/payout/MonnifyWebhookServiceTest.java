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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonnifyWebhookServiceTest {
    private static final String SECRET = "monnify-webhook-test-secret";
    private static final String BODY = "{\"eventType\":\"DISBURSEMENT_SUCCESSFUL\",\"eventData\":{" +
            "\"transactionReference\":\"out-000000000042\",\"amount\":100.00,\"currency\":\"NGN\"}}";
    private JdbcTemplate jdbc;
    private OutboxWriter outbox;
    private MonnifyWebhookService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        outbox = mock(OutboxWriter.class);
        service = new MonnifyWebhookService(jdbc, new ObjectMapper(), outbox, SECRET);
    }

    @Test
    void verifiesRawBodySignatureAndWritesAStatusTrigger() throws Exception {
        when(jdbc.update(anyString(), anyString())).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        service.record(BODY, sign(BODY));
        verify(outbox).append(eq("payment.payout.updated"), eq("out-42"), eq("out-42"), any());
    }

    @Test
    void rejectsInvalidSignatureBeforeParsingOrWriting() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.record(BODY, "0".repeat(128))).getStatusCode());
        verifyNoInteractions(jdbc, outbox);
    }

    @Test
    void returnsUnavailableWhenWebhookSecretIsNotConfigured() throws Exception {
        ReflectionTestUtils.setField(service, "secret", "");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> service.record(BODY, sign(BODY))).getStatusCode());
    }

    @Test
    void duplicateEventDoesNotWriteAnotherOutboxMessage() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        when(jdbc.update(anyString(), anyString())).thenThrow(new DuplicateKeyException("duplicate"));
        service.record(BODY, sign(BODY));
        verifyNoInteractions(outbox);
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
