package org.elibanking.walletservice.enquiry;

import org.elibanking.walletservice.TestTokens;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.AccountHolder;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient.Bank;
import org.elibanking.walletservice.client.PaymentServiceClient.Resolution;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The name enquiry and the bank list (wallet-plan 8.4), over HTTP with real MySQL and Redis. Each provider is mocked: account-service
 * for our own bank, payment-service for the others.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NameEnquiryTest {

    private static final String OWN_CODE = "999";

    @Autowired
    MockMvc mvc;

    @Autowired
    NameEnquiryService enquiries;

    @Autowired
    NameEnquiryRepository rows;

    @MockitoBean
    AccountServiceClient accounts;

    @MockitoBean
    PaymentServiceClient payments;

    @Test
    void anActiveAccountOnOurOwnBankGivesItsNameAndAReference() throws Exception {
        String user = newUser();
        String account = newAccount();
        when(accounts.holder(account)).thenReturn(new AccountHolder("Alice Example", "ACTIVE"));

        mvc.perform(enquiry(user, OWN_CODE, account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("Alice Example"))
                .andExpect(jsonPath("$.nameEnquiryRef").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void aBlockedAccountOnOurOwnBankIsRefused() throws Exception {
        String user = newUser();
        String account = newAccount();
        when(accounts.holder(account)).thenReturn(new AccountHolder("Alice Example", "BLOCKED"));

        mvc.perform(enquiry(user, OWN_CODE, account))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Name enquiry is invalid or expired"));
    }

    @Test
    void aFoundAccountAtAnotherBankGivesItsName() throws Exception {
        String user = newUser();
        String account = newAccount();
        when(payments.resolveAccount("001", account)).thenReturn(new Resolution("FOUND", "Bob Example"));

        mvc.perform(enquiry(user, "001", account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("Bob Example"));
    }

    @Test
    void aMissingAccountAtAnotherBankIsRefused() throws Exception {
        String user = newUser();
        String account = newAccount();
        when(payments.resolveAccount("001", account)).thenReturn(new Resolution("NOT_FOUND", null));

        mvc.perform(enquiry(user, "001", account))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Name enquiry is invalid or expired"));
    }

    @Test
    void aProviderThatCannotAnswerRefusesWith503() throws Exception {
        String user = newUser();
        String account = newAccount();
        when(payments.resolveAccount("001", account)).thenReturn(new Resolution("UNAVAILABLE", null));

        mvc.perform(enquiry(user, "001", account)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void aUserIsLimitedToTwentyEnquiriesABurst() throws Exception {
        String user = newUser();
        String account = newAccount();
        when(payments.resolveAccount("001", account)).thenReturn(new Resolution("FOUND", "Bob Example"));
        for (int i = 0; i < 20; i++) {
            mvc.perform(enquiry(user, "001", account)).andExpect(status().isOk());
        }

        mvc.perform(enquiry(user, "001", account)).andExpect(status().isTooManyRequests());
    }

    @Test
    void aReferenceIsCheckedForTheCallerTheBankTheNumberAndTheExpiry() {
        String owner = newUser();
        String account = newAccount();
        when(payments.resolveAccount("001", account)).thenReturn(new Resolution("FOUND", "Bob Example"));
        NameEnquiryService.Result made = enquiries.enquire(owner, "001", account);

        assertEquals(16, Base64.getUrlDecoder().decode(made.ref()).length, "the reference contains 128 random bits");
        assertEquals("Bob Example", enquiries.validate(owner, made.ref(), "001", account).getAccountName());

        assertRefused(() -> enquiries.validate(newUser(), made.ref(), "001", account), "another user");
        assertRefused(() -> enquiries.validate(owner, made.ref(), "002", account), "another bank");
        assertRefused(() -> enquiries.validate(owner, made.ref(), "001", newAccount()), "another number");
        assertRefused(() -> enquiries.validate(owner, null, "001", account), "no reference");
    }

    @Test
    void anExpiredReferenceIsRefused() {
        String owner = newUser();
        String account = newAccount();
        NameEnquiry expired = new NameEnquiry();
        expired.setRef("expired-" + UUID.randomUUID());
        expired.setUserId(owner);
        expired.setBankCode("001");
        expired.setAccountNumber(account);
        expired.setAccountName("Old Name");
        expired.setCreatedAt(Instant.now().minusSeconds(3600));
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        rows.save(expired);

        assertRefused(() -> enquiries.validate(owner, expired.getRef(), "001", account), "expired");
    }

    @Test
    void theBankListIncludesOurOwnBank() throws Exception {
        when(payments.listBanks()).thenReturn(List.of(new Bank("Bank Alpha", "001")));

        mvc.perform(get("/api/v1/banks").header("Authorization", "Bearer " + TestTokens.userToken(newUser(), "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("001"))
                .andExpect(jsonPath("$[0].name").value("Bank Alpha"))
                .andExpect(jsonPath("$[1].code").value(OWN_CODE))
                .andExpect(jsonPath("$[1].name").value("Test Own Bank"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder enquiry(String user, String bankCode,
                                                                                               String account) {
        return post("/api/v1/transfers/name-enquiry")
                .header("Authorization", "Bearer " + TestTokens.userToken(user, "USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankCode\":\"" + bankCode + "\",\"accountNumber\":\"" + account + "\"}");
    }

    private static void assertRefused(Runnable check, String why) {
        RequestRefusedException refused = assertThrows(RequestRefusedException.class, check::run, why);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, refused.status(), why);
        assertEquals("Name enquiry is invalid or expired", refused.getMessage(), why);
    }

    private static String newUser() {
        return "user-" + UUID.randomUUID();
    }

    private static String newAccount() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }
}
