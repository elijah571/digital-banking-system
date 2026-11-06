package org.elibanking.frauddetectionservice.controller;

import org.elibanking.common.security.ServiceTokens;
import org.elibanking.frauddetectionservice.TestcontainersConfiguration;
import org.elibanking.frauddetectionservice.client.AccountServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The payout fraud check (wallet-plan 3.5, step 3): wallet-service only, with a service token, and the same rules as a transfer. */
@SpringBootTest(properties = "internal-auth.jwt-secret=" + FraudCheckControllerTest.SECRET)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FraudCheckControllerTest {

    static final String SECRET = "fraud-detection-service-test-secret-0123456789";
    private static final String PATH = "/internal/v1/fraud/check";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountServiceClient accounts;

    @Test
    void aCleanPayoutIsNotFraud() throws Exception {
        String account = account();
        when(accounts.getBalance(account)).thenReturn(new BigDecimal("1000000.00"));

        mvc.perform(check(token("wallet-service"), "WITHDRAWAL", account, "10.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraud").value(false));
    }

    @Test
    void aPayoutThatIsMostOfTheBalanceIsFraud() throws Exception {
        String account = account();
        when(accounts.getBalance(account)).thenReturn(new BigDecimal("1000.00"));

        mvc.perform(check(token("wallet-service"), "WITHDRAWAL", account, "950.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraud").value(true))
                .andExpect(jsonPath("$.reason").value("Transaction exceed allowed percentage of account balance"));
    }

    @Test
    void theTypeIsCheckedButTheRulesAreTheSameForBothKinds() throws Exception {
        String account = account();
        when(accounts.getBalance(account)).thenReturn(new BigDecimal("1000000.00"));

        mvc.perform(check(token("wallet-service"), "EXTERNAL_TRANSFER", account, "10.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraud").value(false));
        mvc.perform(check(token("wallet-service"), "BANANA", account, "10.00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherServiceIsRefused() throws Exception {
        mvc.perform(check(token("transaction-service"), "WITHDRAWAL", account(), "10.00"))
                .andExpect(status().isForbidden());
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                        .content("{\"type\":\"WITHDRAWAL\",\"accountNumber\":\"" + account() + "\",\"amount\":\"10.00\"}"))
                .andExpect(status().isUnauthorized());
    }

    private static String token(String subject) {
        return new ServiceTokens(SECRET, subject, "fraud-detection-service").token();
    }

    private static String account() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder check(String token, String type,
                                                                                             String account, String amount) {
        return post(PATH).header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                .content("{\"type\":\"" + type + "\",\"accountNumber\":\"" + account + "\",\"amount\":\"" + amount + "\"}");
    }
}
