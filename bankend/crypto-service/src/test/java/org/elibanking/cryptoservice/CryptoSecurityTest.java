package org.elibanking.cryptoservice;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.elibanking.common.security.AdminRbac;
import org.elibanking.cryptoservice.asset.CryptoAssetService;
import org.elibanking.cryptoservice.config.CryptoSecurityConfig;
import org.elibanking.cryptoservice.controller.CryptoController;
import org.elibanking.cryptoservice.service.CryptoWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = CryptoController.class, properties = {
        "auth.jwt-secret=" + CryptoSecurityTest.SECRET,
        "auth.jwt-issuer=elibanking-auth"})
@Import(CryptoSecurityConfig.class)
class CryptoSecurityTest {
    static final String SECRET = "crypto-user-secret-for-tests-0123456789";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CryptoAssetService assets;

    @MockitoBean
    CryptoWorkflowService workflow;

    @Test
    void anAuditorCanReadCryptoTransactionsButCannotRequestAWithdrawal() throws Exception {
        String bearer = "Bearer " + auditorToken("auditor-1");
        mvc.perform(get("/api/v1/crypto/transactions").header("Authorization", bearer))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/crypto/withdrawals").header("Authorization", bearer)
                        .header("Idempotency-Key", "auditor-withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetKey\":\"ETH\",\"amount\":\"1\",\"destinationAddress\":\"0x0000000000000000000000000000000000000001\",\"pin\":\"1234\"}"))
                .andExpect(status().isForbidden());
    }

    private static String auditorToken(String subject) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("elibanking-auth").subject(subject)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600)))
                .claim("role", "ADMIN").claim("roles", List.of("ROLE_AUDITOR"))
                .claim("authorities", AdminRbac.permissionsFor("ROLE_AUDITOR").stream().sorted().toList())
                .claim("email", subject + "@example.test").build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
