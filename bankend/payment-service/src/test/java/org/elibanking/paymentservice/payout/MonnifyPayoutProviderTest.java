package org.elibanking.paymentservice.payout;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MonnifyPayoutProviderTest {

    private static final String BASE = "https://sandbox.monnify.com";

    @Test
    void listsBanksAlphabeticallyAndUsesV2NameEnquiryAndPayoutContracts() {
        RestClient.Builder apiBuilder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer api = MockRestServiceServer.bindTo(apiBuilder).build();
        RestClient.Builder authBuilder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer auth = MockRestServiceServer.bindTo(authBuilder).build();
        auth.expect(requestTo(BASE + "/api/v1/auth/login")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"requestSuccessful":true,"responseBody":{"accessToken":"sandbox-token","expiresIn":3600}}
                        """, MediaType.APPLICATION_JSON));
        MonnifyTokenManager tokens = new MonnifyTokenManager(authBuilder.build(), "api-key", "secret-key");
        MonnifyPayoutProvider provider = new MonnifyPayoutProvider(apiBuilder.build(), tokens, "0123456789");

        api.expect(requestTo(BASE + "/api/v1/sdk/transactions/banks")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"requestSuccessful":true,"responseBody":[{"name":"Zenith bank","code":"057"},
                        {"name":"Access bank","code":"044"}]}
                        """, MediaType.APPLICATION_JSON));
        api.expect(requestTo(BASE + "/api/v2/disbursements/account/validate?accountNumber=0123456780&bankCode=058"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer sandbox-token"))
                .andRespond(withSuccess("""
                        {"requestSuccessful":true,"responseBody":{"accountNumber":"0123456780","accountName":"Ada Okafor","bankCode":"058"}}
                        """, MediaType.APPLICATION_JSON));
        api.expect(requestTo(BASE + "/api/v2/disbursements/single")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sandbox-token"))
                .andExpect(content().json("""
                        {"amount":1200.50,"reference":"out-000000000042","narration":"Rent",
                         "destinationBankCode":"058","destinationAccountNumber":"0123456780",
                         "destinationAccountName":"Ada Okafor","currency":"NGN","sourceAccountNumber":"0123456789"}
                        """))
                .andRespond(withSuccess("""
                        {"requestSuccessful":true,"responseBody":{"amount":1200.50,"reference":"out-000000000042","status":"SUCCESS"}}
                        """, MediaType.APPLICATION_JSON));

        assertEquals(List.of(new PayoutProvider.Bank("Access bank", "044"),
                new PayoutProvider.Bank("Zenith bank", "057")), provider.listBanks());
        assertEquals(new PayoutProvider.Resolution(PayoutProvider.Outcome.FOUND, "Ada Okafor"),
                provider.validateAccount("058", "0123456780"));
        assertEquals(new PayoutProvider.SendResult("out-000000000042", PayoutProvider.SendStatus.ACCEPTED),
                provider.initiatePayout(new PayoutProvider.PayoutRequest("out-42", new BigDecimal("1200.50"), "058",
                        "0123456780", "Ada Okafor", "Rent")));

        auth.verify();
        api.verify();
    }

    @Test
    void statusLookupMapsMonnifyFinalStatusAndFactsToInternalPayoutKey() {
        RestClient.Builder apiBuilder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer api = MockRestServiceServer.bindTo(apiBuilder).build();
        RestClient.Builder authBuilder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer auth = MockRestServiceServer.bindTo(authBuilder).build();
        auth.expect(requestTo(BASE + "/api/v1/auth/login")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"requestSuccessful":true,"responseBody":{"accessToken":"sandbox-token","expiresIn":3600}}
                        """, MediaType.APPLICATION_JSON));
        MonnifyPayoutProvider provider = new MonnifyPayoutProvider(apiBuilder.build(),
                new MonnifyTokenManager(authBuilder.build(), "api-key", "secret-key"), "0123456789");
        api.expect(requestTo(BASE + "/api/v2/disbursements/single/summary?reference=out-000000000042"))
                .andExpect(header("Authorization", "Bearer sandbox-token"))
                .andRespond(withSuccess("""
                        {"requestSuccessful":true,"responseBody":{"amount":1200.50,"reference":"out-000000000042",
                          "currency":"NGN","status":"COMPLETED"}}
                        """, MediaType.APPLICATION_JSON));

        assertEquals(new PayoutProvider.StatusResult(PayoutProvider.PayoutStatus.SUCCESS, "out-42",
                new BigDecimal("1200.50"), "NGN"), provider.checkStatus("out-42"));
        auth.verify();
        api.verify();
    }
}
