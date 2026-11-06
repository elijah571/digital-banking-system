package org.elibanking.authservice.service;

import org.elibanking.authservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Redis down: the check fails before any password is compared (threat model T13, fail closed). Plain mocks, no context. */
class PasswordCheckServiceTest {

    @Test
    void redisDownMeansNoPasswordIsCompared() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("redis is down"));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(values);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        PasswordCheckService service = new PasswordCheckService(
                redis, mock(UserRepository.class), encoder, mock(JdbcTemplate.class));

        assertThrows(RedisConnectionFailureException.class,
                () -> service.verify("some-user", "a-password", "wallet-service"));

        verify(encoder, never()).matches(any(), any());
    }
}
