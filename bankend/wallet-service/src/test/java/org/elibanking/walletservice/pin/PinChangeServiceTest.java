package org.elibanking.walletservice.pin;

import feign.FeignException;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.client.AuthServiceClient;
import org.elibanking.walletservice.client.AuthServiceClient.PasswordCheck;
import org.elibanking.walletservice.exception.PasswordCheckUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Setting and changing the PIN (wallet-plan 3.5; threat model T2, T5, T7, D-P3, D-P12). Real Redis and MySQL. Kafka and
 * auth-service are mocks, so each test controls the password answer and can read the code and the notices.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PinChangeServiceTest {

    private static final String PASSWORD = "the-owners-password";

    @Autowired
    PinChangeService changes;

    @Autowired
    PinOtpService codes;

    @Autowired
    PinService pins;

    @Autowired
    PinCredentialRepository credentials;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    KafkaTemplate<String, Object> kafka;

    @MockitoBean
    AuthServiceClient auth;

    @Test
    void aFirstPinIsSetAndALaterChangeReplacesIt() {
        String user = newUser();
        when(auth.verifyPassword(any())).thenReturn(new PasswordCheck(true));

        assertEquals(PinChangeService.Result.SET, change(user, codeFor(user), "1234").result());
        assertEquals(PinService.Outcome.VALID, pins.check(user, "1234", user).outcome());

        clearCooldown(user);
        assertEquals(PinChangeService.Result.CHANGED, change(user, codeFor(user), "5678").result());
        assertEquals(PinService.Outcome.VALID, pins.check(user, "5678", user).outcome());
        assertEquals(PinService.Outcome.WRONG, pins.check(user, "1234", user).outcome(), "the old PIN no longer works");
        verify(kafka, times(2)).send(eq("wallet.pin.changed"), eq(user), any());
    }

    @Test
    void aWrongCodeNeverReachesThePasswordCheck() {
        String user = newUser();
        String code = codeFor(user);
        String wrong = code.equals("000000") ? "000001" : "000000";

        PinChangeService.Outcome outcome = change(user, wrong, "1234");

        assertEquals(PinChangeService.Result.WRONG_CODE, outcome.result());
        assertEquals(4, outcome.codeTriesLeft());
        verify(auth, never()).verifyPassword(any());
    }

    @Test
    void aWrongPasswordChangesNothingAndIsCountedAgainstThePin() {
        String user = newUser();
        when(auth.verifyPassword(any())).thenReturn(new PasswordCheck(false));

        assertEquals(PinChangeService.Result.WRONG_PASSWORD, change(user, codeFor(user), "1234").result());

        assertEquals(false, credentials.findById(user).isPresent(), "no PIN was set");
    }

    @Test
    void aWrongPasswordOnAnExistingPinIsCounted() {
        String user = aUserWithPin("1111");
        when(auth.verifyPassword(any())).thenReturn(new PasswordCheck(false));

        assertEquals(PinChangeService.Result.WRONG_PASSWORD, change(user, codeFor(user), "2222").result());

        assertEquals(1, failedCount(user), "a wrong password is one failure against the PIN (D-P3)");
        assertEquals(PinService.Outcome.WRONG, pins.check(user, "2222", user).outcome(), "and the PIN did not change");
    }

    @Test
    void aRateLimitedPasswordCheckIsPassedOn() {
        String user = newUser();
        FeignException limited = mock(FeignException.class);
        when(limited.status()).thenReturn(429);
        when(auth.verifyPassword(any())).thenThrow(limited);

        PinChangeService.Outcome outcome = change(user, codeFor(user), "1234");

        assertEquals(PinChangeService.Result.PASSWORD_RATE_LIMITED, outcome.result());
        assertEquals(PinChangeService.PASSWORD_WINDOW_SECONDS, outcome.retryAfterSeconds());
    }

    @Test
    void anUnreachableAuthServiceFailsClosed() {
        String user = newUser();
        when(auth.verifyPassword(any())).thenThrow(new IllegalStateException("connection refused"));
        String code = codeFor(user);

        assertThrows(PasswordCheckUnavailableException.class, () -> change(user, code, "1234"));
        assertNull(credentials.findById(user).orElse(null), "nothing was written");
    }

    @Test
    void aSuccessfulChangeClearsALock() {
        String user = aUserWithPin("1111");
        jdbc.update("UPDATE pin_credential SET failed_count = 5, lockout_level = 1, locked_until = ? WHERE user_id = ?",
                LocalDateTime.now().plus(Duration.ofMinutes(20)), user);
        when(auth.verifyPassword(any())).thenReturn(new PasswordCheck(true));

        assertEquals(PinChangeService.Result.CHANGED, change(user, codeFor(user), "3333").result(),
                "the owner's code and password are enough to recover (D-P12)");

        assertNull(jdbc.queryForObject("SELECT locked_until FROM pin_credential WHERE user_id = ?", LocalDateTime.class, user));
        assertEquals(0, failedCount(user));
        assertEquals(0, lockoutLevel(user));
        assertEquals(PinService.Outcome.VALID, pins.check(user, "3333", user).outcome());
    }

    @Test
    void theNoticeGoesToTheOwnerAfterTheChange() {
        String user = newUser();
        when(auth.verifyPassword(any())).thenReturn(new PasswordCheck(true));

        change(user, codeFor(user), "4444");

        verify(kafka).send(eq("wallet.pin.changed"), eq(user), any());
    }

    // ------------------------------------------------------------------ helpers

    private PinChangeService.Outcome change(String user, String code, String pin) {
        return changes.change(user, user + "@example.test", code, PASSWORD, pin, user);
    }

    private String newUser() {
        String user = "user-" + UUID.randomUUID();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        return user;
    }

    private String aUserWithPin(String pin) {
        String user = newUser();
        credentials.saveAndFlush(new PinCredential(user, new PinHasher("wallet-test-pepper-0123456789abcdefghij").hash(user, pin)));
        return user;
    }

    /** Asks for a code (clearing the minute's wait first) and reads it from the event that would go to the email. */
    private String codeFor(String user) {
        clearCooldown(user);
        codes.issue(user, user + "@example.test", user);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafka, atLeastOnce()).send(eq("notification.otp"), eq(user), event.capture());
        Object last = event.getAllValues().get(event.getAllValues().size() - 1);
        return String.valueOf(((Map<?, ?>) last).get("otp"));
    }

    private void clearCooldown(String user) {
        redis.delete("pin-otp:cooldown:" + user);
    }

    private int failedCount(String user) {
        return jdbc.queryForObject("SELECT failed_count FROM pin_credential WHERE user_id = ?", Integer.class, user);
    }

    private int lockoutLevel(String user) {
        return jdbc.queryForObject("SELECT lockout_level FROM pin_credential WHERE user_id = ?", Integer.class, user);
    }
}
