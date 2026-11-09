package org.elibanking.paymentservice.payout;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpMethod;

class PaystackPayoutProviderTest {

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
