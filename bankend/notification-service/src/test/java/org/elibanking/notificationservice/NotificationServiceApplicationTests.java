package org.elibanking.notificationservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "spring.mail.username=",
        "spring.mail.password=",
        "internal-auth.jwt-secret=notification-service-test-secret-0123456789"
})
@Import(TestcontainersConfiguration.class)
class NotificationServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
