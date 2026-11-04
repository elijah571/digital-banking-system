package org.elibanking.transactionservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "internal-auth.jwt-secret=transaction-service-test-secret-0123456789")
@Import(TestcontainersConfiguration.class)
class TransactionServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
