package org.elibanking.paymentservice.payout;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import org.springframework.http.HttpMethod;

class PaystackPayoutProviderTest {

    @Test
    void configuredPaystackClientUsesAnIdentifyingUserAgent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.paystack.co/bank"))
                .andExpect(header("User-Agent", "EliBanking-Payment-Service/1.0"))
                .andExpect(header("Authorization", "Bearer sk_test_example"))
                .andRespond(withSuccess("{\"status\":true,\"data\":[]}", MediaType.APPLICATION_JSON));

        PayoutProviderConfiguration.paystackClient(builder, "sk_test_example", "https://api.paystack.co")
                .get().uri("/bank").retrieve().body(String.class);

        server.verify();
    }

    @Test
    void listsBanksUsingPaystacksCursorPagination() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.paystack.co/bank?currency=NGN&country=nigeria&use_cursor=true&perPage=100"))
                .andExpect(queryParam("use_cursor", "true"))
                .andRespond(withSuccess("""
                        {"status":true,"data":[{"name":"Bank A","code":"001"},{"name":"Bank B","code":"002"}],
                        "meta":{"next":"cursor-2"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.paystack.co/bank?currency=NGN&country=nigeria&use_cursor=true&perPage=100&next=cursor-2"))
                .andExpect(queryParam("next", "cursor-2"))
                .andRespond(withSuccess("""
                        {"status":true,"data":[{"name":"Bank C","code":"003"}],"meta":{"next":null}}
                        """, MediaType.APPLICATION_JSON));
        PaystackPayoutProvider provider = new PaystackPayoutProvider(builder.baseUrl("https://api.paystack.co").build(),
                mock(PaystackRecipientRepository.class));

        assertEquals(List.of(new PayoutProvider.Bank("Bank A", "001"), new PayoutProvider.Bank("Bank B", "002"),
                new PayoutProvider.Bank("Bank C", "003")), provider.listBanks());
        server.verify();
    }

    @Test
    void createsTransfersWithPaddedReferenceAndKoboAmount() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.paystack.co");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaystackRecipientRepository recipients = mock(PaystackRecipientRepository.class);
        when(recipients.find("058", "0123456789")).thenReturn(Optional.of("RCP_demo"));
        server.expect(requestTo("https://api.paystack.co/transfer"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount").value(12345))
                .andExpect(jsonPath("$.reference").value("out-000000000001"))
                .andExpect(jsonPath("$.recipient").value("RCP_demo"))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"status\":\"pending\",\"transfer_code\":\"TRF_1\"}}",
                        MediaType.APPLICATION_JSON));
        PaystackPayoutProvider provider = new PaystackPayoutProvider(builder.build(), recipients);

        PayoutProvider.SendResult result = provider.send("out-1", new BigDecimal("123.45"), "058", "0123456789",
                "Test Person", "test transfer");

        assertEquals("TRF_1", result.providerRef());
        assertEquals(PayoutProvider.SendStatus.PENDING, result.status());
        server.verify();
    }

    @Test
    void definitivePaystackTransferRejectionReturnsFailedForWalletRelease() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.paystack.co");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaystackRecipientRepository recipients = mock(PaystackRecipientRepository.class);
        when(recipients.find("057", "0000000000")).thenReturn(Optional.of("RCP_test"));
        server.expect(requestTo("https://api.paystack.co/transfer"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":false,\"message\":\"recipient rejected\"}"));
        PaystackPayoutProvider provider = new PaystackPayoutProvider(builder.build(), recipients);

        PayoutProvider.SendResult result = provider.send("out-23", new BigDecimal("50.00"), "057", "0000000000",
                "Test Recipient", "test payout");

        assertEquals(PayoutProvider.SendStatus.FAILED, result.status());
        assertEquals("out-000000000023", result.providerRef());
        server.verify();
    }

    @Test
    void rateLimitAndDuplicateReferenceRemainAmbiguousForStatusRecovery() {
        for (var response : List.of(
                withStatus(HttpStatus.TOO_MANY_REQUESTS).body("rate limited"),
                withStatus(HttpStatus.BAD_REQUEST).body("Please provide a unique reference. Reference already exists"))) {
            RestClient.Builder builder = RestClient.builder().baseUrl("https://api.paystack.co");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            PaystackRecipientRepository recipients = mock(PaystackRecipientRepository.class);
            when(recipients.find("057", "0000000000")).thenReturn(Optional.of("RCP_test"));
            server.expect(requestTo("https://api.paystack.co/transfer")).andRespond(response);
            PaystackPayoutProvider provider = new PaystackPayoutProvider(builder.build(), recipients);

            assertThrows(org.springframework.web.client.HttpClientErrorException.class,
                    () -> provider.send("out-24", new BigDecimal("50.00"), "057", "0000000000",
                            "Test Recipient", "test payout"));
            server.verify();
        }
    }

    @Test
    void statusIncludesCanonicalReferenceAmountAndCurrency() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.paystack.co");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.paystack.co/transfer/verify/out-000000000001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"reference\":\"out-000000000001\","
                        + "\"amount\":12345,\"currency\":\"NGN\",\"status\":\"success\"}}", MediaType.APPLICATION_JSON));
        PaystackPayoutProvider provider = new PaystackPayoutProvider(builder.build(), mock(PaystackRecipientRepository.class));

        PayoutProvider.StatusResult result = provider.getStatusDetails("out-1");

        assertEquals(PayoutProvider.PayoutStatus.SUCCESS, result.status());
        assertEquals("out-1", result.reference());
        assertEquals(new BigDecimal("123.45"), result.amount());
        assertEquals("NGN", result.currency());
        server.verify();
    }

    @Test
    void rejectsFractionalKoboBeforeCallingProvider() {
        PaystackPayoutProvider provider = new PaystackPayoutProvider(RestClient.builder().build(),
                mock(PaystackRecipientRepository.class));
        assertThrows(ArithmeticException.class, () -> provider.send("out-1", new BigDecimal("1.001"),
                "058", "0123456789", "Test Person", "test"));
    }
}
