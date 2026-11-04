package org.elibanking.transactionservice.config;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.elibanking.common.security.ServiceTokens;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServiceAuthTest {

    private static final String SECRET = "transaction-service-test-secret-0123456789";

    @Test
    void everyCallToAccountServiceCarriesAServiceTokenNamingTransactionService() throws Exception {
        ServiceTokens tokens = new ServiceTokens(SECRET, "transaction-service", "account-service");
        RequestInterceptor interceptor = new AccountServiceAuth().accountServiceTokenInterceptor(tokens);

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        Collection<String> values = template.headers().get("Authorization");
        assertNotNull(values, "the Feign request has an Authorization header");
        assertEquals(1, values.size());
        String header = values.iterator().next();
        assertTrue(header.startsWith("Bearer "), header);

        SignedJWT jwt = SignedJWT.parse(header.substring("Bearer ".length()));
        assertTrue(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8))));
        assertEquals("transaction-service", jwt.getJWTClaimsSet().getSubject());
        assertEquals(List.of("account-service"), jwt.getJWTClaimsSet().getAudience());
        assertEquals("SERVICE", jwt.getJWTClaimsSet().getStringClaim("role"));
    }
}
