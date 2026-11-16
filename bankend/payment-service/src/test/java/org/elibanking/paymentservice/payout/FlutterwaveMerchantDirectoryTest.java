package org.elibanking.paymentservice.payout;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FlutterwaveMerchantDirectoryTest {

    private static final String BASE = "https://api.flutterwave.com/v3";

    @Test
    void loadsEveryPageAndSortsByBusinessNameWithoutExposingBankDetails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FlutterwaveMerchantDirectory directory = new FlutterwaveMerchantDirectory(
                PayoutProviderConfiguration.flutterwaveClient(builder, "FLWSECK_TEST-x", BASE));
        server.expect(requestTo(BASE + "/subaccounts?page=1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","meta":{"page_info":{"total_pages":2}},"data":[
                          {"id":2,"business_name":"Zulu Stores","account_number":"1234567890"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/subaccounts?page=2")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","meta":{"page_info":{"total_pages":2}},"data":[
                          {"id":3,"business_name":"alpha market","account_number":"0987654321"},
                          {"id":4,"business_name":"Beta Foods","account_number":"1111111111"}]}
                        """, MediaType.APPLICATION_JSON));

        List<MerchantDirectory.Merchant> result = directory.list();

        assertEquals(List.of(new MerchantDirectory.Merchant("3", "alpha market"),
                new MerchantDirectory.Merchant("4", "Beta Foods"),
                new MerchantDirectory.Merchant("2", "Zulu Stores")), result);
        server.verify();
    }
}
