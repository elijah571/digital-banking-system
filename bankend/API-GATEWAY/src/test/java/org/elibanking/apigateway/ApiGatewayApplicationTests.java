package org.elibanking.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "auth.jwt-secret=test-secret-test-secret-test-secret-0123456789"
})
@Import(TestcontainersConfiguration.class)
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {
    }

}
