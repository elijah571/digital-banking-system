package org.elibanking.paymentservice;

import org.elibanking.paymentservice.security.UserTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The bank list and the name enquiry (wallet-plan 8.4, 8.7): wallet-service only, and the simulator's three outcomes. */
@SpringBootTest(properties = {"auth.jwt-secret=" + UserTokens.SECRET, "internal-auth.jwt-secret=" + TestTokens.SERVICE_SECRET})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalPayoutControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void walletGetsTheBankList() throws Exception {
        mvc.perform(get("/internal/v1/banks")
                        .header("Authorization", "Bearer " + TestTokens.serviceTokenAs("wallet-service")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").exists())
                .andExpect(jsonPath("$[0].name").exists());
    }

    @Test
    void aFoundAccountGivesItsName() throws Exception {
        resolve("001", "0123456785").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOUND"))
                .andExpect(jsonPath("$.accountName").value("SIMULATED ACCOUNT 6785"));
    }

    @Test
    void aNumberEndingInZeroIsNotFound() throws Exception {
        resolve("001", "0123456780").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }

    @Test
    void aNumberEndingInNineIsUnavailable() throws Exception {
        resolve("001", "0123456789").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"));
    }

    @Test
    void anUnknownBankIsNotFound() throws Exception {
        resolve("999", "0123456785").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }

    @Test
    void otherServicesAreRefused() throws Exception {
        mvc.perform(get("/internal/v1/banks")
                        .header("Authorization", "Bearer " + TestTokens.serviceTokenAs("transaction-service")))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions resolve(String bankCode, String accountNumber) throws Exception {
        return mvc.perform(post("/internal/v1/accounts/resolve")
                .header("Authorization", "Bearer " + TestTokens.serviceTokenAs("wallet-service"))
                .contentType(APPLICATION_JSON)
                .content("{\"bankCode\":\"" + bankCode + "\",\"accountNumber\":\"" + accountNumber + "\"}"));
    }
}
