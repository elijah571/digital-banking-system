package org.elibanking.walletservice.enquiry;

import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient.Bank;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankListServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void usesCachedProviderBanksAndAddsOwnBank() {
        PaymentServiceClient payments = mock(PaymentServiceClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(BankListService.CACHE_KEY)).thenReturn("[{\"name\":\"Provider Bank\",\"code\":\"001\"}]");
        BankListService service = new BankListService(payments, redis, new ObjectMapper(), "Our Bank", "999",
                Duration.ofHours(24));

        assertEquals(List.of(new Bank("Provider Bank", "001"), new Bank("Our Bank", "999")), service.list());
        verify(payments, never()).listBanks();
    }

    @Test
    @SuppressWarnings("unchecked")
    void providerBankLookupFailsOpenWhenRedisIsUnavailable() {
        PaymentServiceClient payments = mock(PaymentServiceClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(BankListService.CACHE_KEY)).thenThrow(new DataAccessResourceFailureException("test outage"));
        when(payments.listBanks()).thenReturn(List.of(new Bank("Provider Bank", "001")));
        BankListService service = new BankListService(payments, redis, new ObjectMapper(), "Our Bank", "999",
                Duration.ofHours(24));

        assertEquals(List.of(new Bank("Provider Bank", "001"), new Bank("Our Bank", "999")), service.list());
        verify(payments).listBanks();
    }
}
