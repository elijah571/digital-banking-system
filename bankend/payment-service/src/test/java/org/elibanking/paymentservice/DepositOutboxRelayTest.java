package org.elibanking.paymentservice;

import org.elibanking.paymentservice.outbox.OutboxRelay;
import org.elibanking.paymentservice.security.UserTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The relay sends a recorded deposit event to Kafka once, and marks its row sent (wallet-plan 3.8). */
@SpringBootTest(properties = {
        "auth.jwt-secret=" + UserTokens.SECRET,
        "internal-auth.jwt-secret=" + TestTokens.SERVICE_SECRET,
        "stripe.webhook-secret=" + DepositWebhookTest.WEBHOOK_SECRET,
        "outbox.relay.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DepositOutboxRelayTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxRelay relay;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    KafkaTemplate<String, Object> kafka;

    @Test
    void aRecordedDepositEventIsPublishedOnceAndMarkedSent() throws Exception {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        String deposit = "dep-" + UUID.randomUUID();
        String event = "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"object\":\"event\",\"api_version\":\"" + DepositWebhookTest.API_VERSION
                + "\",\"type\":\"payment_intent.succeeded\","
                + "\"data\":{\"object\":{\"id\":\"pi_" + UUID.randomUUID() + "\",\"object\":\"payment_intent\","
                + "\"amount\":2000000,\"amount_received\":2000000,\"currency\":\"ngn\",\"metadata\":{\"depositId\":\"" + deposit + "\"}}}}";
        mvc.perform(post("/api/v1/payments/webhook").contentType(APPLICATION_JSON)
                        .header("Stripe-Signature", DepositWebhookTest.sign(event, DepositWebhookTest.WEBHOOK_SECRET))
                        .content(event))
                .andExpect(status().isOk());

        relay.publishPending();
        relay.publishPending();

        verify(kafka, org.mockito.Mockito.times(1)).send(eq("payment.intent.succeeded"), eq(deposit), any());
        assertNotNull(jdbc.queryForObject("SELECT sent_at FROM outbox WHERE aggregate_id = ?", java.sql.Timestamp.class, deposit));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_id = ?", Integer.class, deposit));
    }
}
