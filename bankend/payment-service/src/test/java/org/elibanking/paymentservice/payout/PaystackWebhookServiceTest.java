package org.elibanking.paymentservice.payout;

import org.elibanking.paymentservice.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaystackWebhookServiceTest {

    private static final String SECRET = "sk_test_example_secret";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private PaystackWebhookService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new PaystackWebhookService(jdbc, outbox, new ObjectMapper());
        var secret = PaystackWebhookService.class.getDeclaredField("secretKey");
        secret.setAccessible(true);
        secret.set(service, SECRET);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
    }

    @Test
    void verifiesSignatureAndRecordsAStableTrigger() throws Exception {
        String body = "{\"event\":\"transfer.success\",\"data\":{\"reference\":\"out-0000000000001\",\"amount\":1200,\"currency\":\"NGN\"}}";
        service.verifySignature(body, sign(body));

        service.record(body);

        verify(jdbc).update(any(String.class), eq(PaystackWebhookService.CONSUMER), eq("transfer.success:out-0000000000001"));
        verify(outbox).append(eq(PaystackWebhookService.SUCCESS_TOPIC), eq("out-1"), eq("out-1"), any());
    }

    @Test
    void rejectsInvalidSignature() {
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.verifySignature("{}", "0".repeat(128))).getStatusCode().value());
    }

    @Test
    void duplicateProviderEventIsNotWrittenToTheOutboxAgain() {
        String body = "{\"event\":\"transfer.failed\",\"data\":{\"reference\":\"out-0000000000001\"}}";
        when(jdbc.update(any(String.class), any(Object[].class))).thenThrow(new DuplicateKeyException("duplicate event"));

        service.record(body);

        verify(outbox, never()).append(any(String.class), any(String.class), any(String.class), any());
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
