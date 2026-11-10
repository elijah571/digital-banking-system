package org.elibanking.walletservice.enquiry;

import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NameEnquiryServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void refusesWith503BeforeProviderLookupWhenRateLimitRedisIsUnavailable() {
        NameEnquiryRepository enquiries = mock(NameEnquiryRepository.class);
        AccountServiceClient accounts = mock(AccountServiceClient.class);
        PaymentServiceClient payments = mock(PaymentServiceClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(NameEnquiryService.RATE_KEY + "user-1"))
                .thenThrow(new RedisConnectionFailureException("test outage"));
        NameEnquiryService service = new NameEnquiryService(enquiries, accounts, payments, redis, "999", 20,
                Duration.ofMinutes(10));

        RequestRefusedException refused = assertThrows(RequestRefusedException.class,
                () -> service.enquire("user-1", "058", "0123456789"));

        assertEquals(503, refused.status().value());
        verify(payments, org.mockito.Mockito.never()).resolveAccount(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
