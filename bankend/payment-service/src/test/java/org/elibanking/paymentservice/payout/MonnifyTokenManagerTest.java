package org.elibanking.paymentservice.payout;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MonnifyTokenManagerTest {

    private static final String BASE = "https://sandbox.monnify.com";

    @Test
    void authenticatesWithBasicApiKeySecretAndCachesToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String expected = "Basic " + Base64.getEncoder().encodeToString("api-key:secret-key".getBytes());
        server.expect(requestTo(BASE + "/api/v1/auth/login")).andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", expected))
                .andRespond(withSuccess("""
                        {"requestSuccessful":true,"responseBody":{"accessToken":"cached-token","expiresIn":3600}}
                        """, MediaType.APPLICATION_JSON));
        MonnifyTokenManager manager = new MonnifyTokenManager(builder.build(), "api-key", "secret-key");

        assertEquals("cached-token", manager.getToken());
        assertEquals("cached-token", manager.getToken());
        server.verify();
    }

    @Test
    void concurrentRequestsShareOneLogin() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicInteger calls = new AtomicInteger();
        server.expect(requestTo(BASE + "/api/v1/auth/login")).andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(request -> {
                    calls.incrementAndGet();
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return withSuccess("""
                            {"requestSuccessful":true,"responseBody":{"accessToken":"one-token","expiresIn":3600}}
                            """, MediaType.APPLICATION_JSON).createResponse(request);
                });
        MonnifyTokenManager manager = new MonnifyTokenManager(builder.build(), "api-key", "secret-key",
                Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC));
        int count = 8;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            for (int i = 0; i < count; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    assertEquals("one-token", manager.getToken());
                    return null;
                });
            }
            ready.await(2, TimeUnit.SECONDS);
            start.countDown();
        }
        assertEquals(1, calls.get());
        server.verify();
    }
}
