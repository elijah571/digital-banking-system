package org.elibanking.transactionservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.elibanking.transactionservice.security.UserTokens;

@SpringBootTest(properties = {"internal-auth.jwt-secret=transaction-service-test-secret-0123456789", "otp.hmac-key=transaction-service-test-otp-hmac-key-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET, "outbox.relay.enabled=false", "transfer.recovery.enabled=false"})
@Import(TestcontainersConfiguration.class)
class TransactionServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
