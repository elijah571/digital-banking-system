package org.elibanking.notificationservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "spring.mail.username=",
        "spring.mail.password="
})
@Import(TestcontainersConfiguration.class)
class NotificationServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
