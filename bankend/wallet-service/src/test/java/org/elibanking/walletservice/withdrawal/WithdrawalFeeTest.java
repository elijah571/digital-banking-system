package org.elibanking.walletservice.withdrawal;

import org.elibanking.walletservice.TestTokens;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.LedgerLine;
import org.elibanking.walletservice.client.AccountServiceClient.PostResult;
import org.elibanking.walletservice.client.FraudClient;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient.Resolution;
import org.elibanking.walletservice.enquiry.NameEnquiryService;
import org.elibanking.walletservice.pin.PinService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "wallet.outbound-fee=25.00")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawalFeeTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    NameEnquiryService enquiries;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AccountServiceClient accounts;

    @MockitoBean
    PaymentServiceClient payments;

    @MockitoBean
    PinService pins;

    @MockitoBean
    FraudClient fraud;

    @Test
    void holdCreditsFeeRevenueAndRejectionRefundsTheFee() throws Exception {
        String user = "user-" + UUID.randomUUID();
        String account = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        String destination = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        when(accounts.check(account)).thenReturn(new AccountServiceClient.AccountCheck(user, "ACTIVE", "NGN"));
        when(accounts.dailyLimit(account)).thenReturn(new BigDecimal("1000000.00"));
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new PostResult(1L, false));
        when(payments.resolveAccount(eq("001"), eq(destination))).thenReturn(new Resolution("FOUND", "Bob Example"));
        when(pins.check(eq(user), anyString(), eq(user))).thenReturn(new PinService.Check(PinService.Outcome.VALID, 0));
        when(fraud.check(eq("WITHDRAWAL"), eq(account), any())).thenReturn(Optional.empty());
        String enquiry = enquiries.enquire(user, "001", destination).ref();

        MvcResult request = mvc.perform(post("/api/v1/wallet/withdrawals")
                        .header("Authorization", "Bearer " + TestTokens.userToken(user, "USER"))
                        .header("Idempotency-Key", "fee-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"" + account + "\",\"amount\":\"150000.00\",\"bankCode\":\"001\","
                                + "\"destinationAccountNumber\":\"" + destination + "\",\"nameEnquiryRef\":\"" + enquiry
                                + "\",\"narration\":\"rent\",\"pin\":\"1234\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        long id = new tools.jackson.databind.ObjectMapper().readTree(request.getResponse().getContentAsString())
                .get("payoutId").asLong();

        mvc.perform(post("/api/v1/wallet/withdrawals/" + id + "/reject")
                        .header("Authorization", "Bearer " + TestTokens.userToken("admin-" + UUID.randomUUID(), "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test fee refund\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<List<LedgerLine>> entries = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<String> kinds = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(accounts, times(2)).postEntry(kinds.capture(), anyString(), anyString(), anyString(), entries.capture());
        assertEquals(List.of("OUTBOUND_HOLD", "OUTBOUND_RELEASE"), kinds.getAllValues());

        List<LedgerLine> hold = entries.getAllValues().getFirst();
        assertEquals("150025.00", hold.getFirst().amount());
        assertEquals("150000.00", hold.get(1).amount());
        assertEquals("FEE_REVENUE:NGN", hold.get(2).systemAccount());
        assertEquals("25.00", hold.get(2).amount());
        assertEquals("CREDIT", hold.get(2).side());

        List<LedgerLine> release = entries.getAllValues().get(1);
        assertEquals("PAYOUT_PAYABLE:NGN", release.getFirst().systemAccount());
        assertEquals("FEE_REVENUE:NGN", release.get(1).systemAccount());
        assertEquals("25.00", release.get(1).amount());
        assertEquals("DEBIT", release.get(1).side());
        assertEquals("150025.00", release.get(2).amount());
        assertEquals("0.00", jdbc.queryForObject("SELECT total FROM outbound_daily_usage WHERE account_number = ?",
                BigDecimal.class, account).setScale(2).toPlainString());
    }
}
