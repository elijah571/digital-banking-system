package org.elibanking.paymentservice;

import org.elibanking.paymentservice.security.UserTokens;
import org.elibanking.paymentservice.stripe.StripeIntents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The internal intent endpoint (wallet-plan 3.5, G1): wallet-service only, NGN only, and the amount is converted to kobo exactly.
 * Stripe is a mock, so no network call is made.
 */
@SpringBootTest(properties = {"auth.jwt-secret=" + UserTokens.SECRET, "internal-auth.jwt-secret=" + TestTokens.SERVICE_SECRET})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalPaymentControllerTest {

    private static final String PATH = "/internal/v1/payments/intents";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    StripeIntents stripe;

    @Test
    void walletGetsAnIntentInKobo() throws Exception {
        when(stripe.create(eq("dep-1"), eq(2000000L), eq("NGN")))
                .thenReturn(new StripeIntents.Created("pi_test_1", "pi_test_1_secret"));

        mvc.perform(post(PATH).header("Authorization", "Bearer " + TestTokens.serviceTokenAs("wallet-service"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"depositId\":\"dep-1\",\"amount\":\"20000.00\",\"currency\":\"NGN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentIntentId").value("pi_test_1"))
                .andExpect(jsonPath("$.clientSecret").value("pi_test_1_secret"));
    }

    @Test
    void otherServicesAreRefused() throws Exception {
        mvc.perform(request(TestTokens.serviceTokenAs("transaction-service"), "{\"depositId\":\"d\",\"amount\":\"1.00\",\"currency\":\"NGN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aUserTokenIsNotAServiceToken() throws Exception {
        mvc.perform(request(UserTokens.of("alice", "USER"), "{\"depositId\":\"d\",\"amount\":\"1.00\",\"currency\":\"NGN\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyNairaIsAccepted() throws Exception {
        mvc.perform(request(TestTokens.serviceTokenAs("wallet-service"),
                        "{\"depositId\":\"d\",\"amount\":\"1.00\",\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAmountWithMoreThanTwoDecimalsIsRefused() throws Exception {
        mvc.perform(request(TestTokens.serviceTokenAs("wallet-service"),
                        "{\"depositId\":\"d\",\"amount\":\"1.001\",\"currency\":\"NGN\"}"))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder request(String token, String body) {
        return post(PATH).header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON).content(body);
    }
}
