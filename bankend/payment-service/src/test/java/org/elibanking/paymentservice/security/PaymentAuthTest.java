package org.elibanking.paymentservice.security;

import org.elibanking.paymentservice.TestcontainersConfiguration;
import org.elibanking.paymentservice.client.AccountClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D8 (wallet-plan 3.7): payment-service checks the user token itself, and X-User-* headers are never identity. The webhook stays
 * public, and still refuses a bad signature.
 */
@SpringBootTest(properties = {"stripe.secret-key=", "stripe.webhook-secret=",
        "internal-auth.jwt-secret=payment-service-test-secret-0123456789", "auth.jwt-secret=" + UserTokens.SECRET})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentAuthTest {

    private static final String PATH = "/api/v1/payments/create-order";
    private static final String ACCOUNT = "3693612260";
    private static final String BODY = "{\"amount\":25.50,\"accountNumber\":\"" + ACCOUNT + "\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountClient accountClient;

    @Test
    void aForgedUserIdHeaderWithNoTokenIsRefused() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("X-User-Id", "user-b").header("X-User-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(accountClient);
    }

    @Test
    void aUserCannotPayIntoSomeoneElsesAccount() throws Exception {
        when(accountClient.getAccount(ACCOUNT)).thenReturn(new AccountClient.AccountInfo(ACCOUNT, "user-b", "ACTIVE"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer " + UserTokens.of("user-a", "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenWithNoSubjectIsRefused() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer " + UserTokens.withoutSubject()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(accountClient);
    }

    @Test
    void theWebhookStaysPublicAndStillRejectsABadSignature() throws Exception {
        mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Stripe-Signature", "t=1,v1=bad"))
                .andExpect(status().isBadRequest());
    }
}
