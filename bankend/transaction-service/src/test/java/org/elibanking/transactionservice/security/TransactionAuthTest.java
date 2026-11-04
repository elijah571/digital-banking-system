package org.elibanking.transactionservice.security;

import org.elibanking.transactionservice.TestcontainersConfiguration;
import org.elibanking.transactionservice.client.AccountInfo;
import org.elibanking.transactionservice.client.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D8 (wallet-plan 3.7): transaction-service checks the user token itself, and X-User-* headers are never identity. The
 * gateway path is covered by the smoke test, which runs through it.
 */
@SpringBootTest(properties = {"internal-auth.jwt-secret=transaction-service-test-secret-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TransactionAuthTest {

    private static final String PATH = "/api/v1/transactions";
    private static final String SENDER = "1000000001";
    private static final String BODY = "{\"senderAccountNumber\":\"" + SENDER + "\",\"receiverAccountNumber\":\"1000000002\","
            + "\"amount\":10.00,\"type\":\"TRANSFER\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountService accountService;

    @Test
    void aForgedUserIdHeaderWithNoTokenIsRefused() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("X-User-Id", "user-b").header("X-User-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(accountService);
    }

    @Test
    void aUserCannotTransferFromSomeoneElsesAccount() throws Exception {
        when(accountService.getAccount(SENDER)).thenReturn(new AccountInfo(SENDER, "user-b", "ACTIVE"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer " + UserTokens.of("user-a", "USER")))
                .andExpect(status().isForbidden());
        verify(accountService, never()).deductBalance(anyString(), any());
    }

    @Test
    void anAdminCannotTransferFromSomeoneElsesAccountEither() throws Exception {
        when(accountService.getAccount(SENDER)).thenReturn(new AccountInfo(SENDER, "user-b", "ACTIVE"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer " + UserTokens.of("admin-1", "ADMIN")))
                .andExpect(status().isForbidden());
        verify(accountService, never()).deductBalance(anyString(), any());
    }

    @Test
    void theOwnerCanTransferFromTheirOwnAccount() throws Exception {
        when(accountService.getAccount(SENDER)).thenReturn(new AccountInfo(SENDER, "user-a", "ACTIVE"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer " + UserTokens.of("user-a", "USER")))
                .andExpect(status().isCreated());
        verify(accountService).deductBalance(eq(SENDER), any());
    }

    @Test
    void aTokenWithNoSubjectIsRefused() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer " + UserTokens.withoutSubject()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(accountService);
    }
}
