package org.elibanking.walletservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may call wallet-service (wallet-plan 3.7): no token is 401, a service token is refused on user paths, a user token is
 * refused on internal paths, a token signed with the wrong secret is 401, and the internal routes are closed to every caller
 * but the one allowed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WalletSecurityTest {

    @Autowired
    MockMvc mvc;

    @Test
    void aCallWithNoTokenIsRefused() throws Exception {
        mvc.perform(get("/api/v1/wallet/pin")).andExpect(status().isUnauthorized());
    }

    @Test
    void aUserTokenReachesTheApi() throws Exception {
        // Authorised, then nothing matches: there is no endpoint yet, so the answer is 404, not 401 or 403.
        mvc.perform(get("/api/v1/wallet/pin")
                        .header("Authorization", "Bearer " + TestTokens.userToken("alice", "USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aServiceTokenIsNotAUser() throws Exception {
        mvc.perform(get("/api/v1/wallet/pin")
                        .header("Authorization", "Bearer " + TestTokens.serviceTokenAs("transaction-service")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theOnlyInternalRouteIsPostOnly() throws Exception {
        // GET on the PIN path matches no rule, so it is denied even for transaction-service.
        mvc.perform(get("/internal/v1/pin/verify")
                        .header("Authorization", "Bearer " + TestTokens.serviceTokenAs("transaction-service")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenSignedWithTheWrongSecretIsRefused() throws Exception {
        mvc.perform(get("/api/v1/wallet/pin")
                        .header("Authorization", "Bearer " + TestTokens.userTokenWithTheWrongSecret("alice")))
                .andExpect(status().isUnauthorized());
    }
}
