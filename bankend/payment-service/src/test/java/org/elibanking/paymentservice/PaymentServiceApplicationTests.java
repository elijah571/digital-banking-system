package org.elibanking.paymentservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "stripe.secret-key=",
        "stripe.webhook-secret="
})
@Import(TestcontainersConfiguration.class)
class PaymentServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
