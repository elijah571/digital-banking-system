package org.elibanking.apigateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gateway's admin route and the statement route, against a stub account-service. What is checked here is the gateway's
 * behaviour: who may reach a path, and what identity and headers the upstream receives. Ownership of a statement is checked
 * by account-service, so it is not tested here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "auth.jwt-secret=" + AdminRoutesTest.SECRET)
@Import(TestcontainersConfiguration.class)
class AdminRoutesTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    private static final String RECONCILIATION = "/api/v1/admin/ledger/reconciliation";
    private static final String CREDIT = "/api/v1/admin/accounts/1234567890/credit";
    private static final String STATEMENT = "/api/v1/accounts/1234567890/statement";

    /** Every request the stub received, in order. */
    private static final Queue<Upstream> RECEIVED = new ConcurrentLinkedQueue<>();
    private static final HttpServer UPSTREAM = startUpstream();

    record Upstream(String method, String path, String query, String authorization, String userId, String userRole,
                    String idempotencyKey) {
    }

    record Response(int status, String body) {
    }

    @LocalServerPort
    int port;

    private RestClient client;

    @DynamicPropertySource
    static void accountService(DynamicPropertyRegistry registry) {
        registry.add("ACCOUNT_SERVICE_URL", () -> "http://localhost:" + UPSTREAM.getAddress().getPort());
    }

    @BeforeEach
    void setUp() {
        RECEIVED.clear();
        client = RestClient.create("http://localhost:" + port);
    }

    @Test
    void anUnauthenticatedAdminCallIsRejectedAndNeverReachesAccountService() {
        assertEquals(401, call(HttpMethod.GET, RECONCILIATION, null).status());
        assertTrue(RECEIVED.isEmpty(), "nothing reaches account-service without a token");
    }

    @Test
    void aUserIsForbiddenOnAdminRoutes() {
        String user = token("user-1", "USER");

        assertEquals(403, call(HttpMethod.GET, RECONCILIATION, user).status());
        assertEquals(403, call(HttpMethod.PUT, CREDIT + "?amount=1.00&reason=x", user, "Idempotency-Key", "k-1").status());
        assertTrue(RECEIVED.isEmpty(), "nothing reaches account-service for a user");
    }

    @Test
    void anAdminReachesAccountServiceWithTheVerifiedIdentity() {
        String admin = token("admin-1", "ADMIN");

        assertEquals(200, call(HttpMethod.GET, RECONCILIATION, admin).status());

        Upstream seen = RECEIVED.poll();
        assertEquals("GET", seen.method());
        assertEquals(RECONCILIATION, seen.path());
        assertEquals("Bearer " + admin, seen.authorization(), "the token is forwarded for account-service to verify");
        assertEquals("admin-1", seen.userId());
        assertEquals("ADMIN", seen.userRole());
    }

    @Test
    void adminCreditPassesThroughWithItsQueryAndIdempotencyKey() {
        String admin = token("admin-1", "ADMIN");

        assertEquals(200, call(HttpMethod.PUT, CREDIT + "?amount=50.00&reason=bonus", admin,
                "Idempotency-Key", "adj-42").status());

        Upstream seen = RECEIVED.poll();
        assertEquals("PUT", seen.method());
        assertEquals(CREDIT, seen.path());
        assertEquals("amount=50.00&reason=bonus", seen.query());
        assertEquals("adj-42", seen.idempotencyKey(), "account-service requires the key, so the gateway must forward it");
    }

    @Test
    void aForgedIdentityHeaderDoesNotElevateAUser() {
        String user = token("user-1", "USER");

        assertEquals(403, call(HttpMethod.GET, RECONCILIATION, user, "X-User-Role", "ADMIN").status(),
                "a client-sent role header is ignored; the token says USER");
        assertTrue(RECEIVED.isEmpty());

        assertEquals(200, call(HttpMethod.GET, STATEMENT, user, "X-User-Id", "admin-1", "X-User-Role", "ADMIN").status());
        Upstream seen = RECEIVED.poll();
        assertEquals("user-1", seen.userId(), "the gateway replaces the client's X-User-Id with the token's subject");
        assertEquals("USER", seen.userRole(), "the gateway replaces the client's X-User-Role with the token's role");
    }

    @Test
    void theStatementIsRoutedForAnyLoggedInUserAndNeedsAToken() {
        assertEquals(200, call(HttpMethod.GET, STATEMENT, token("user-1", "USER")).status(),
                "the gateway lets any logged-in user through; account-service checks ownership");
        Upstream seen = RECEIVED.poll();
        assertEquals("GET", seen.method());
        assertEquals(STATEMENT, seen.path());

        assertEquals(401, call(HttpMethod.GET, STATEMENT, null).status());
        assertTrue(RECEIVED.isEmpty(), "the unauthenticated call did not reach the upstream");
    }

    // ------------------------------------------------------------------ helpers

    private Response call(HttpMethod method, String uri, String bearer, String... headers) {
        ResponseEntity<String> entity = client.method(method).uri(uri)
                .headers(h -> {
                    if (bearer != null) {
                        h.setBearerAuth(bearer);
                    }
                    for (int i = 0; i < headers.length; i += 2) {
                        h.set(headers[i], headers[i + 1]);
                    }
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toEntity(String.class);
        return new Response(entity.getStatusCode().value(), entity.getBody());
    }

    static String token(String subject, String role) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("elibanking-auth")
                    .subject(subject)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(600)))
                    .claim("role", role)
                    .claim("email", subject + "@example.test")
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not sign a test token", e);
        }
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", AdminRoutesTest::record);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void record(HttpExchange exchange) throws IOException {
        RECEIVED.add(new Upstream(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), header(exchange, "Authorization"), header(exchange, "X-User-Id"),
                header(exchange, "X-User-Role"), header(exchange, "Idempotency-Key")));
        byte[] body = "{\"upstream\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }
}
