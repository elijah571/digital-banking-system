package org.elibanking.walletservice.pin;

import org.elibanking.walletservice.Concurrently;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.exception.PinOtpCooldownException;
import org.elibanking.walletservice.exception.PinOtpUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The PIN code (wallet-plan 3.5; threat model T2, T10, D-P4, D-P11). Real Redis and MySQL; Kafka is a mock, so each test can read
 * the event that would have gone to notification-service.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PinOtpServiceTest {

    @Autowired
    PinOtpService codes;

    @Autowired
    StringRedisTemplate redis;

    @MockitoBean
    KafkaTemplate<String, Object> kafka;

    @Test
    void theCodeIsStoredHashedAndSentOnceByEmail() {
        String user = "user-" + UUID.randomUUID();
        String email = user + "@example.test";
        whenKafkaAccepts();

        codes.issue(user, email, user);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(PinOtpService.TOPIC), eq(user), event.capture());
        Map<?, ?> sent = (Map<?, ?>) event.getValue();
        String code = String.valueOf(sent.get("otp"));
        assertEquals("PIN_CHANGE", sent.get("purpose"));
        assertEquals(email, sent.get("email"));
        assertTrue(code.matches("\\d{6}"), "six digits");
        String stored = redis.opsForValue().get("pin-otp:code:" + user);
        assertFalse(stored.contains(code), "Redis does not hold the code");
        assertEquals(codes.hash(user, code), stored, "Redis holds the keyed hash");
    }

    @Test
    void aRightCodeWorksOnceOnly() {
        String user = issueFor();
        String code = codeSentTo(user);

        assertEquals(PinOtpService.Result.VALID, codes.consume(user, code, user).result());
        assertEquals(PinOtpService.Result.NONE, codes.consume(user, code, user).result(), "a second use finds nothing");
    }

    @Test
    void fiveWrongCodesEndTheCodeAndTheRightOneIsThenRefused() {
        String user = issueFor();
        String code = codeSentTo(user);
        String wrong = code.equals("000000") ? "000001" : "000000";

        for (int left = 4; left >= 0; left--) {
            PinOtpService.Attempt attempt = codes.consume(user, wrong, user);
            assertEquals(PinOtpService.Result.WRONG, attempt.result());
            assertEquals(left, attempt.attemptsLeft());
        }

        assertEquals(PinOtpService.Result.REFUSED, codes.consume(user, code, user).result(),
                "the sixth try is refused even with the right code");
    }

    @Test
    void tenParallelWrongCodesGetFiveComparisonsAndFiveRefusals() throws Exception {
        String user = issueFor();
        String code = codeSentTo(user);
        String wrong = code.equals("000000") ? "000001" : "000000";

        List<Object> outcomes = Concurrently.sameCall(10, () -> codes.consume(user, wrong, user));

        assertEquals(5, countOf(outcomes, PinOtpService.Result.WRONG), "exactly five were compared");
        assertEquals(5, countOf(outcomes, PinOtpService.Result.REFUSED), "the other five were refused");
    }

    @Test
    void aRightCodeSentTwiceAtOnceSucceedsOnce() throws Exception {
        String user = issueFor();
        String code = codeSentTo(user);

        List<Object> outcomes = Concurrently.sameCall(2, () -> codes.consume(user, code, user));

        assertEquals(1, countOf(outcomes, PinOtpService.Result.VALID), "one of the two succeeds");
        assertEquals(1, outcomes.stream().filter(o -> o instanceof PinOtpService.Attempt a
                && a.result() != PinOtpService.Result.VALID).count(), "the other is refused");
    }

    @Test
    void aSecondCodeWithinAMinuteIsRefusedWithRetryAfter() {
        String user = issueFor();

        PinOtpCooldownException refused = assertThrows(PinOtpCooldownException.class,
                () -> codes.issue(user, user + "@example.test", user));
        assertTrue(refused.retryAfterSeconds() > 0 && refused.retryAfterSeconds() <= 60);
    }

    @Test
    void aCodeThatCannotBeSentIsNotLeftWaitingFor() {
        String user = "user-" + UUID.randomUUID();
        when(kafka.send(anyString(), anyString(), any())).thenThrow(new IllegalStateException("broker is down"));

        assertThrows(PinOtpUnavailableException.class, () -> codes.issue(user, user + "@example.test", user));

        assertEquals(null, redis.opsForValue().get("pin-otp:code:" + user), "no code is left behind");
        assertEquals(null, redis.opsForValue().get("pin-otp:cooldown:" + user), "the user may ask again at once");
    }

    // ------------------------------------------------------------------ helpers

    private String issueFor() {
        String user = "user-" + UUID.randomUUID();
        whenKafkaAccepts();
        codes.issue(user, user + "@example.test", user);
        return user;
    }

    private String codeSentTo(String user) {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafka, org.mockito.Mockito.atLeastOnce()).send(eq(PinOtpService.TOPIC), eq(user), event.capture());
        return String.valueOf(((Map<?, ?>) event.getAllValues().get(event.getAllValues().size() - 1)).get("otp"));
    }

    private void whenKafkaAccepts() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private static long countOf(List<Object> outcomes, PinOtpService.Result result) {
        return outcomes.stream().filter(o -> o instanceof PinOtpService.Attempt a && a.result() == result).count();
    }
}
