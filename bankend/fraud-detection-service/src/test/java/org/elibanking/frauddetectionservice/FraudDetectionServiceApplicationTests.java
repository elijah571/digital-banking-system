package org.elibanking.frauddetectionservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "internal-auth.jwt-secret=fraud-detection-service-test-secret-0123456789")
@Import(TestcontainersConfiguration.class)
class FraudDetectionServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
