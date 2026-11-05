package org.elibanking.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "auth.jwt-secret=test-secret-test-secret-test-secret-0123456789",
        "internal-auth.jwt-secret=" + TestTokens.SERVICE_SECRET,
        "auth.admin-email=",
        "auth.admin-password="
})
@Import(TestcontainersConfiguration.class)
class AuthServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
