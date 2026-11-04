package org.elibanking.paymentservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "stripe.secret-key=",
        "stripe.webhook-secret=",
        "internal-auth.jwt-secret=payment-service-test-secret-0123456789"
})
@Import(TestcontainersConfiguration.class)
class PaymentServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
