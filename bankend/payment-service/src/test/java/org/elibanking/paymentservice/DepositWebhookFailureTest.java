package org.elibanking.paymentservice;

import org.elibanking.paymentservice.outbox.OutboxWriter;
import org.elibanking.paymentservice.security.UserTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * A deposit event that cannot be recorded is not swallowed: the webhook answers 500 over HTTP, so Stripe retries it, and the
 * failed attempt leaves no processed-event row and no outbox row (wallet-plan 3.5, 3.8).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.jwt-secret=" + UserTokens.SECRET,
        "internal-auth.jwt-secret=" + TestTokens.SERVICE_SECRET,
        "stripe.webhook-secret=" + DepositWebhookTest.WEBHOOK_SECRET,
        "outbox.relay.enabled=false"})
@Import(TestcontainersConfiguration.class)
class DepositWebhookFailureTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OutboxWriter outbox;

    @Test
    void aRecordingFailureAnswersFiveHundredAndRecordsNothing() throws Exception {
        doThrow(new IllegalStateException("outbox is unavailable"))
                .when(outbox).append(anyString(), anyString(), anyString(), any());
        String eventId = "evt_" + UUID.randomUUID();
        String deposit = "dep-" + UUID.randomUUID();
        String event = "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\"" + DepositWebhookTest.API_VERSION
                + "\",\"type\":\"payment_intent.succeeded\","
                + "\"data\":{\"object\":{\"id\":\"pi_" + UUID.randomUUID() + "\",\"object\":\"payment_intent\","
                + "\"amount\":2000000,\"amount_received\":2000000,\"currency\":\"ngn\","
                + "\"metadata\":{\"depositId\":\"" + deposit + "\"}}}}";

        int status = RestClient.create("http://localhost:" + port).post()
                .uri("/api/v1/payments/webhook")
                .contentType(APPLICATION_JSON)
                .header("Stripe-Signature", DepositWebhookTest.sign(event, DepositWebhookTest.WEBHOOK_SECRET))
                .body(event)
                .exchange((request, response) -> response.getStatusCode().value());

        assertEquals(500, status, "Stripe must see a 500 so it retries the event");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE consumer = 'stripe-webhook' AND event_id = ?",
                Integer.class, eventId), "the failed attempt rolls back its processed-event row");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ?", Integer.class, deposit),
                "the failed attempt leaves no outbox row");
    }
}
