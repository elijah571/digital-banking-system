package org.elibanking.walletservice.pin;

import org.elibanking.walletservice.TestTokens;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.elibanking.walletservice.client.AuthServiceClient;
import org.elibanking.walletservice.client.AuthServiceClient.PasswordCheck;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The set-or-change endpoint: who may call it, and what each answer is (wallet-plan 3.5). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PinChangeControllerTest {

    private static final String PATH = "/api/v1/wallet/pin";
    private static final String PASSWORD = "the-owners-password";

    @Autowired
    MockMvc mvc;

    @Autowired
    PinOtpService codes;

    @MockitoBean
    KafkaTemplate<String, Object> kafka;

    @MockitoBean
    AuthServiceClient auth;

    @Test
    void theOwnerSetsAPinWithTheCodeAndThePassword() throws Exception {
        String user = "user-" + UUID.randomUUID();
        String code = codeFor(user);
        when(auth.verifyPassword(any())).thenReturn(new PasswordCheck(true));

        mvc.perform(change(TestTokens.userToken(user, "USER"), "{\"code\":\"" + code + "\",\"password\":\"" + PASSWORD
                        + "\",\"pin\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PIN_SET"));
    }

    @Test
    void aWrongCodeIsABadRequestThatSaysHowManyTriesRemain() throws Exception {
        String user = "user-" + UUID.randomUUID();
        String code = codeFor(user);
        String wrong = code.equals("000000") ? "000001" : "000000";

        mvc.perform(change(TestTokens.userToken(user, "USER"), "{\"code\":\"" + wrong + "\",\"password\":\"" + PASSWORD
                        + "\",\"pin\":\"1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Incorrect code. 4 tries left."));
    }

    @Test
    void aWrongPasswordIsForbidden() throws Exception {
        String user = "user-" + UUID.randomUUID();
        String code = codeFor(user);
        when(auth.verifyPassword(any())).thenReturn(new PasswordCheck(false));

        mvc.perform(change(TestTokens.userToken(user, "USER"), "{\"code\":\"" + code + "\",\"password\":\"nope\""
                        + ",\"pin\":\"1234\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Wrong password."));
    }

    @Test
    void aServiceTokenCannotChangeAPin() throws Exception {
        mvc.perform(change(TestTokens.serviceTokenAs("transaction-service"),
                "{\"code\":\"123456\",\"password\":\"x\",\"pin\":\"1234\"}")).andExpect(status().isForbidden());
    }

    @Test
    void aMalformedPinIsABadRequest() throws Exception {
        mvc.perform(change(TestTokens.userToken("someone", "USER"),
                "{\"code\":\"123456\",\"password\":\"x\",\"pin\":\"12\"}")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private String codeFor(String user) {
        when(kafka.send(anyRecord())).thenReturn(CompletableFuture.completedFuture(null));
        codes.issue(user, user + "@example.test", user);
        ArgumentCaptor<ProducerRecord<String, Object>> records = ArgumentCaptor.forClass((Class) ProducerRecord.class);
        verify(kafka, atLeastOnce()).send(records.capture());
        ProducerRecord<String, Object> sent = records.getAllValues().stream()
                .filter(record -> "notification.otp".equals(record.topic()) && user.equals(record.key()))
                .reduce((first, last) -> last)
                .orElseThrow(() -> new AssertionError("No PIN OTP record was sent"));
        return String.valueOf(((Map<?, ?>) ((Map<?, ?>) sent.value()).get("payload")).get("otp"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ProducerRecord<String, Object> anyRecord() {
        return (ProducerRecord) any(ProducerRecord.class);
    }

    private static MockHttpServletRequestBuilder change(String token, String body) {
        return put(PATH).header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON).content(body);
    }
}
