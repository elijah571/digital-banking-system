package org.elibanking.paymentservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.elibanking.paymentservice.security.UserTokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = {
        "stripe.secret-key=",
        "stripe.webhook-secret=",
        "internal-auth.jwt-secret=payment-service-test-secret-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET,
        "payout.provider=simulator",
        "payout.monnify.api-key=",
        "payout.monnify.secret-key=",
        "payout.monnify.source-account-number="
})
@Import(TestcontainersConfiguration.class)
class PaymentServiceApplicationTests {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
        assertEquals(1, context.getBeansOfType(org.elibanking.paymentservice.payout.PayoutProvider.class).size());
        assertInstanceOf(org.elibanking.paymentservice.payout.SimulatedPayoutProvider.class,
                context.getBean(org.elibanking.paymentservice.payout.PayoutProvider.class));
    }

}
