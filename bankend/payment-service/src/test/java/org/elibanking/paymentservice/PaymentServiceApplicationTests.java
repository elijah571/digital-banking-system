package org.elibanking.paymentservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.elibanking.paymentservice.security.UserTokens;

@SpringBootTest(properties = {
        "stripe.secret-key=",
        "stripe.webhook-secret=",
        "internal-auth.jwt-secret=payment-service-test-secret-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET
})
@Import(TestcontainersConfiguration.class)
class PaymentServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
