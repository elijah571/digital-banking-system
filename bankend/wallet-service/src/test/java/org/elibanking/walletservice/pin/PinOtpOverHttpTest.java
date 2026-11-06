package org.elibanking.walletservice.pin;

import org.elibanking.common.testsupport.TestImages;
import org.elibanking.walletservice.TestTokens;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The code request over HTTP, through the real security chain, with a real Kafka broker and real Redis (wallet-plan 3.5). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PinOtpOverHttpTest.Broker.class})
class PinOtpOverHttpTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theCodeIsSentOverHttpThroughARealBroker() throws Exception {
        String user = "user-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/wallet/pin/otp")
                        .header("Authorization", "Bearer " + TestTokens.userToken(user, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));
    }

    static class Broker {
        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return TestImages.kafka();
        }
    }
}
