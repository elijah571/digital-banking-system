package org.elibanking.transactionservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.elibanking.transactionservice.security.UserTokens;

@SpringBootTest(properties = {"internal-auth.jwt-secret=transaction-service-test-secret-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET})
@Import(TestcontainersConfiguration.class)
class TransactionServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
