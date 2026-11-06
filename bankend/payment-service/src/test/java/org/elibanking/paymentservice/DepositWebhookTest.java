package org.elibanking.paymentservice;

import org.elibanking.paymentservice.security.UserTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Stripe webhook for a wallet deposit (wallet-plan 3.5, 3.8): a signed event is recorded once, a replay changes nothing, a
 * bad signature records nothing, and an event with no deposit is left to the legacy path. Real MySQL and Kafka.
 */
@SpringBootTest(properties = {
        "auth.jwt-secret=" + UserTokens.SECRET,
        "internal-auth.jwt-secret=" + TestTokens.SERVICE_SECRET,
        "stripe.webhook-secret=" + DepositWebhookTest.WEBHOOK_SECRET,
        "outbox.relay.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DepositWebhookTest {

    static final String WEBHOOK_SECRET = "whsec_test_deposit_webhook_secret_0123456789";
    /** The API version the pinned stripe-java uses. Stripe puts it on every event, and the SDK needs it to read the object. */
    static final String API_VERSION = "2025-05-28.basil";
    private static final String PATH = "/api/v1/payments/webhook";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aSucceededDepositIsRecordedOnceEvenWhenDeliveredTwice() throws Exception {
        String deposit = "dep-" + UUID.randomUUID();
        String eventId = "evt_" + UUID.randomUUID();
        String event = succeeded(eventId, deposit, 2000000L);

        deliver(event).andExpect(status().isOk());
        deliver(event).andExpect(status().isOk());

        assertEquals(1, outboxRows(deposit, "payment.intent.succeeded"), "the replay adds no second row");
        assertEquals(1, processedEvents(eventId), "the event id is recorded once");
        String payload = jdbc.queryForObject("SELECT payload FROM outbox WHERE aggregate_id = ?", String.class, deposit);
        // MySQL stores a JSON column with a space after ':' and ',', so compare the parsed fields, not the text.
        JsonNode body = new ObjectMapper().readTree(payload).get("payload");
        assertEquals("20000.00", body.get("amountReceived").asText());
        assertEquals("NGN", body.get("currency").asText());
    }

    @Test
    void aFailedDepositIsRecordedAsFailedWithTheReason() throws Exception {
        String deposit = "dep-" + UUID.randomUUID();

        deliver(failed("evt_" + UUID.randomUUID(), deposit)).andExpect(status().isOk());

        assertEquals(1, outboxRows(deposit, "payment.intent.failed"));
        String payload = jdbc.queryForObject("SELECT payload FROM outbox WHERE aggregate_id = ?", String.class, deposit);
        assertTrue(payload.contains("Card declined"), payload);
    }

    @Test
    void aBadSignatureIsRefusedAndRecordsNothing() throws Exception {
        String deposit = "dep-" + UUID.randomUUID();
        String eventId = "evt_" + UUID.randomUUID();
        String event = succeeded(eventId, deposit, 2000000L);

        mvc.perform(post(PATH).contentType(APPLICATION_JSON).header("Stripe-Signature", "t=1,v1=00")
                        .content(event))
                .andExpect(status().isBadRequest());

        assertEquals(0, outboxRows(deposit, "payment.intent.succeeded"));
        assertEquals(0, processedEvents(eventId), "a refused event is not recorded"); 
    }

    @Test
    void anEventWithNoDepositIsLeftToTheLegacyPath() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        String event = "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\"" + API_VERSION
                + "\",\"type\":\"payment_intent.succeeded\","
                + "\"data\":{\"object\":{\"id\":\"pi_" + UUID.randomUUID() + "\",\"object\":\"payment_intent\","
                + "\"amount\":100,\"amount_received\":100,\"currency\":\"usd\",\"metadata\":{}}}}";
        deliver(event).andExpect(status().isOk());

        assertEquals(0, processedEvents(eventId), "the legacy path records nothing in the deposit outbox"); 
    }

    // ------------------------------------------------------------------ helpers

    /** How many times this Stripe event id is recorded. The table is shared by the tests in this class, so count by id. */
    private int processedEvents(String eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE consumer = 'stripe-webhook' AND event_id = ?",
                Integer.class, eventId);
    }

    private int outboxRows(String deposit, String topic) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND topic = ?",
                Integer.class, deposit, topic);
    }

    private org.springframework.test.web.servlet.ResultActions deliver(String payload) throws Exception {
        MockHttpServletRequestBuilder request = post(PATH).contentType(APPLICATION_JSON)
                .header("Stripe-Signature", sign(payload, WEBHOOK_SECRET)).content(payload);
        return mvc.perform(request);
    }

    private static String succeeded(String eventId, String deposit, long received) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\"" + API_VERSION
                + "\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":"
                + "{\"id\":\"pi_" + UUID.randomUUID() + "\",\"object\":\"payment_intent\",\"amount\":" + received
                + ",\"amount_received\":" + received + ",\"currency\":\"ngn\",\"metadata\":{\"depositId\":\"" + deposit + "\"}}}}";
    }

    private static String failed(String eventId, String deposit) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\"" + API_VERSION
                + "\",\"type\":\"payment_intent.payment_failed\",\"data\":{\"object\":"
                + "{\"id\":\"pi_" + UUID.randomUUID() + "\",\"object\":\"payment_intent\",\"amount\":2000000,\"amount_received\":0,"
                + "\"currency\":\"ngn\",\"last_payment_error\":{\"message\":\"Card declined\"},"
                + "\"metadata\":{\"depositId\":\"" + deposit + "\"}}}}";
    }

    /** Stripe's scheme: {@code t=<unix>,v1=HMAC-SHA256(secret, "<t>.<payload>")}. */
    static String sign(String payload, String secret) throws Exception {
        long t = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String hex = HexFormat.of().formatHex(mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + t + ",v1=" + hex;
    }
}
