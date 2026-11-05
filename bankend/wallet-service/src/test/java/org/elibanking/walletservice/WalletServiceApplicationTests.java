package org.elibanking.walletservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The context starts against a real MySQL, with the token and PIN secrets set. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WalletServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
