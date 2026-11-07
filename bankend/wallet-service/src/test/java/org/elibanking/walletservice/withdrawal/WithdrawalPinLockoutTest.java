package org.elibanking.walletservice.withdrawal;

import org.elibanking.walletservice.Concurrently;
import org.elibanking.walletservice.TestTokens;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient.Resolution;
import org.elibanking.walletservice.enquiry.NameEnquiryService;
import org.elibanking.walletservice.pin.PinCredential;
import org.elibanking.walletservice.pin.PinCredentialRepository;
import org.elibanking.walletservice.pin.PinHasher;
import org.elibanking.walletservice.pin.PinService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawalPinLockoutTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    NameEnquiryService enquiries;

    @Autowired
    PinCredentialRepository credentials;

    @Autowired
    PinHasher hasher;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AccountServiceClient accounts;

    @MockitoBean
    PaymentServiceClient payments;

    @Autowired
    PinService pins;

    @Test
    void fiftyParallelWrongWithdrawalPinsUseTheExistingLockout() throws Exception {
        String user = "user-" + UUID.randomUUID();
        String account = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        String destination = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        credentials.saveAndFlush(new PinCredential(user, hasher.hash(user, "1234")));
        when(accounts.check(account)).thenReturn(new AccountServiceClient.AccountCheck(user, "ACTIVE", "NGN"));
        when(accounts.dailyLimit(account)).thenReturn(new java.math.BigDecimal("1000000.00"));
        when(payments.resolveAccount(eq("001"), eq(destination))).thenReturn(new Resolution("FOUND", "Bob Example"));
        String enquiry = enquiries.enquire(user, "001", destination).ref();

        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String key = "withdrawal-pin-" + UUID.randomUUID();
            calls.add(() -> mvc.perform(withdrawal(user, account, destination, enquiry, key))
                    .andReturn().getResponse().getStatus());
        }
        List<Object> statuses = Concurrently.together(calls);

        assertEquals(5, statuses.stream().filter(Integer.valueOf(403)::equals).count(),
                "five wrong comparisons are counted through withdrawal");
        assertEquals(45, statuses.stream().filter(Integer.valueOf(423)::equals).count(),
                "all later attempts see the existing PIN lockout");
        assertEquals(5, jdbc.queryForObject("SELECT failed_count FROM pin_credential WHERE user_id = ?", Integer.class, user));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbound_payout WHERE user_id = ?", Integer.class, user));
        assertEquals(PinService.Outcome.LOCKED, pins.check(user, "1234", user).outcome());
    }

    private static MockHttpServletRequestBuilder withdrawal(String user, String account, String destination, String enquiry, String key) {
        return post("/api/v1/wallet/withdrawals")
                .header("Authorization", "Bearer " + TestTokens.userToken(user, "USER"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"" + account + "\",\"amount\":\"5000.00\",\"bankCode\":\"001\","
                        + "\"destinationAccountNumber\":\"" + destination + "\",\"nameEnquiryRef\":\"" + enquiry
                        + "\",\"narration\":\"rent\",\"pin\":\"9999\"}");
    }
}
