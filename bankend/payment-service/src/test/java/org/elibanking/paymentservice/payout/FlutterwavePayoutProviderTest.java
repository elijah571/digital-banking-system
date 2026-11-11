package org.elibanking.paymentservice.payout;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Flutterwave V3 adapter against mocked HTTP (wallet-plan 9.11). No test touches the real API. */
class FlutterwavePayoutProviderTest {

    private static final String BASE = "https://api.flutterwave.com/v3";

    private MockRestServiceServer server;

    private FlutterwavePayoutProvider newProvider() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = PayoutProviderConfiguration.flutterwaveClient(builder, "FLWSECK_TEST-x", BASE);
        return new FlutterwavePayoutProvider(client);
    }

    @Test
    void listsNigerianBanks() {
        FlutterwavePayoutProvider provider = newProvider();
        server.expect(requestTo(BASE + "/banks/NG"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer FLWSECK_TEST-x"))
                .andRespond(withSuccess("""
                        {"status":"success","message":"Banks fetched","data":[
                          {"id":1,"code":"044","name":"Access Bank"},
                          {"id":2,"code":"058","name":"GTBank"}]}""", MediaType.APPLICATION_JSON));

        List<PayoutProvider.Bank> banks = provider.listBanks();

        assertEquals(2, banks.size());
        assertEquals("044", banks.get(0).code());
        assertEquals("Access Bank", banks.get(0).name());
        server.verify();
    }

    @Test
    void resolvesAnAccountName() {
        FlutterwavePayoutProvider provider = newProvider();
        server.expect(requestTo(BASE + "/accounts/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"account_number\":\"0690000031\",\"account_bank\":\"044\"}"))
                .andRespond(withSuccess("""
                        {"status":"success","message":"Account details fetched",
                         "data":{"account_number":"0690000031","account_name":"PASTOR BRIGHT"}}""",
                        MediaType.APPLICATION_JSON));

        PayoutProvider.Resolution resolution = provider.resolveAccount("044", "0690000031");

        assertEquals(PayoutProvider.Outcome.FOUND, resolution.outcome());
        assertEquals("PASTOR BRIGHT", resolution.accountName());
        server.verify();
    }

    @Test
    void anUnresolvableAccountIsNotFound() {
        FlutterwavePayoutProvider provider = newProvider();
        server.expect(requestTo(BASE + "/accounts/resolve"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"status\":\"error\",\"message\":\"Sorry we could not resolve this account\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertEquals(PayoutProvider.Outcome.NOT_FOUND, provider.resolveAccount("044", "0000000000").outcome());
        server.verify();
    }

    @Test
    void initiatesATransferInNairaMajorUnitsAndReturnsPending() {
        FlutterwavePayoutProvider provider = newProvider();
        server.expect(requestTo(BASE + "/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"reference\":\"out-000000000001\"")))
                .andExpect(content().string(containsString("8500")))      // naira
                .andExpect(content().string(not(containsString("850000")))) // not kobo
                .andExpect(content().string(containsString("\"currency\":\"NGN\"")))
                .andRespond(withSuccess("""
                        {"status":"success","message":"Transfer Queued Successfully",
                         "data":{"id":28235,"reference":"out-000000000001","amount":8500.00,"currency":"NGN","status":"NEW"}}""",
                        MediaType.APPLICATION_JSON));

        PayoutProvider.SendResult result = provider.send("out-1", new BigDecimal("8500.00"), "044",
                "0123456789", "PASTOR BRIGHT", "Payout");

        assertEquals(PayoutProvider.SendStatus.PENDING, result.status());
        assertEquals("28235", result.providerRef());
        server.verify();
    }

    @Test
    void aConclusiveRejectionFailsTheSend() {
        FlutterwavePayoutProvider provider = newProvider();
        server.expect(requestTo(BASE + "/transfers"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"status\":\"error\",\"message\":\"Insufficient funds in your balance\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        PayoutProvider.SendResult result = provider.send("out-2", new BigDecimal("100.00"), "044",
                "0123456789", "NAME", "x");

        assertEquals(PayoutProvider.SendStatus.FAILED, result.status());
        assertEquals("out-000000000002", result.providerRef());
        server.verify();
    }

    @Test
    void aRateLimitStaysRecoverable() {
        FlutterwavePayoutProvider provider = newProvider();
        server.expect(requestTo(BASE + "/transfers"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"status\":\"error\",\"message\":\"rate limited\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThrows(HttpClientErrorException.class,
                () -> provider.send("out-3", new BigDecimal("100.00"), "044", "0123456789", "NAME", "x"));
        server.verify();
    }

    @Test
    void aDuplicateReferenceStaysRecoverable() {
        FlutterwavePayoutProvider provider = newProvider();
        server.expect(requestTo(BASE + "/transfers"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"status\":\"error\",\"message\":\"Transaction reference already exists\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThrows(HttpClientErrorException.class,
                () -> provider.send("out-4", new BigDecimal("100.00"), "044", "0123456789", "NAME", "x"));
        server.verify();
    }

    @Test
    void verifiesASuccessfulTransferByReference() {
        FlutterwavePayoutProvider provider = newProvider();
        server.expect(requestTo(BASE + "/transfers?reference=out-000000000001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","data":[
                          {"id":28235,"reference":"out-000000000001","amount":8500.00,"currency":"NGN","status":"SUCCESSFUL"}]}""",
                        MediaType.APPLICATION_JSON));

        PayoutProvider.StatusResult status = provider.getStatusDetails("out-1");

        assertEquals(PayoutProvider.PayoutStatus.SUCCESS, status.status());
        assertEquals("out-1", status.reference());
        assertEquals(0, new BigDecimal("8500.00").compareTo(status.amount()));
        assertEquals("NGN", status.currency());
        server.verify();
    }

    @Test
    void mapsPendingStatus() {
        assertStatus("NEW", PayoutProvider.PayoutStatus.PENDING);
    }

    @Test
    void mapsFailedStatus() {
        assertStatus("FAILED", PayoutProvider.PayoutStatus.FAILED);
    }

    @Test
    void mapsReversedStatus() {
        assertStatus("REVERSED", PayoutProvider.PayoutStatus.REVERSED);
    }

    @Test
    void anUnknownReferenceIsNotFound() {
        FlutterwavePayoutProvider provider = newProvider();
        server.expect(requestTo(BASE + "/transfers?reference=out-000000000009"))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(PayoutProvider.PayoutStatus.NOT_FOUND, provider.getStatusDetails("out-9").status());
        server.verify();
    }

    @Test
    void anErrorEnvelopeIsRejected() {
        FlutterwavePayoutProvider provider = newProvider();
        server.expect(requestTo(BASE + "/transfers?reference=out-000000000001"))
                .andRespond(withSuccess("{\"status\":\"error\",\"message\":\"boom\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> provider.getStatusDetails("out-1"));
        server.verify();
    }

    private void assertStatus(String providerStatus, PayoutProvider.PayoutStatus expected) {
        FlutterwavePayoutProvider provider = newProvider();
        server.expect(requestTo(BASE + "/transfers?reference=out-000000000001"))
                .andRespond(withSuccess("""
                        {"status":"success","data":[
                          {"id":28235,"reference":"out-000000000001","amount":100.00,"currency":"NGN","status":"%s"}]}"""
                        .formatted(providerStatus), MediaType.APPLICATION_JSON));

        assertEquals(expected, provider.getStatusDetails("out-1").status());
        server.verify();
    }
}
