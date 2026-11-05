package org.elibanking.paymentservice.client;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.elibanking.common.security.ServiceTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AccountClientTest {

    private static final String SECRET = "payment-service-test-secret-0123456789";

    @Test
    void theAccountLookupCarriesAServiceTokenNamingPaymentService() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AccountClient client = new AccountClient(builder, "http://account.test",
                new ServiceTokens(SECRET, "payment-service", "account-service"));

        server.expect(requestTo("http://account.test/api/v1/accounts/1234567890"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> {
                    try {
                        assertServiceToken(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
                    } catch (Exception e) {
                        throw new AssertionError("the request did not carry a valid service token", e);
                    }
                })
                .andRespond(withSuccess("{\"accountNumber\":\"1234567890\",\"ownerId\":\"user-1\",\"status\":\"ACTIVE\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals("user-1", client.getAccount("1234567890").ownerId());
        server.verify();
    }

    private static void assertServiceToken(String header) throws Exception {
        assertNotNull(header, "an Authorization header");
        assertTrue(header.startsWith("Bearer "), header);
        SignedJWT jwt = SignedJWT.parse(header.substring("Bearer ".length()));
        assertTrue(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8))), "signed with the shared secret");
        assertEquals("payment-service", jwt.getJWTClaimsSet().getSubject());
        assertEquals(List.of("account-service"), jwt.getJWTClaimsSet().getAudience());
        assertEquals("SERVICE", jwt.getJWTClaimsSet().getStringClaim("role"));
    }
}
