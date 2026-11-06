package org.elibanking.walletservice.pin;

import org.elibanking.walletservice.TestTokens;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The code request endpoint: who may ask, the answer, and the minute between codes (wallet-plan 3.5, D-P11). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PinOtpControllerTest {

    private static final String PATH = "/api/v1/wallet/pin/otp";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    KafkaTemplate<String, Object> kafka;

    @Test
    void aUserGetsACodeByEmail() throws Exception {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        String user = "user-" + UUID.randomUUID();

        mvc.perform(post(PATH).header("Authorization", "Bearer " + TestTokens.userToken(user, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.expiresInSeconds").value(300));
    }

    @Test
    void aSecondCodeWithinAMinuteIsTooManyRequests() throws Exception {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        String user = "user-" + UUID.randomUUID();
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TestTokens.userToken(user, "USER")));

        mvc.perform(post(PATH).header("Authorization", "Bearer " + TestTokens.userToken(user, "USER")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void aServiceTokenCannotAskForACode() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TestTokens.serviceTokenAs("transaction-service")))
                .andExpect(status().isForbidden());
    }

    @Test
    void noTokenIsRefused() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
    }
}
