package org.elibanking.walletservice.pin;

import org.elibanking.walletservice.Concurrently;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PIN check and its lockout (wallet-plan 3.5; threat model T1, T11, D-P2). Real MySQL. Each test uses a fresh user, because
 * the lockout is per user.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PinServiceTest {

    private static final String RIGHT = "1234";
    private static final String WRONG = "9999";

    @Autowired
    PinService pins;

    @Autowired
    PinCredentialRepository credentials;

    @Autowired
    PinHasher hasher;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void fiftyParallelWrongPinsLockAfterTheFifthAndTheRightPinWaits() throws Exception {
        String user = aUserWithPin(RIGHT);

        List<Object> outcomes = Concurrently.sameCall(50, () -> pins.check(user, WRONG, "transaction-service"));

        assertEquals(5, countOf(outcomes, PinService.Outcome.WRONG), "exactly five were compared");
        assertEquals(45, countOf(outcomes, PinService.Outcome.LOCKED), "the rest were refused without a comparison");
        assertEquals(5, failedCount(user), "the fifth failure is the one that locks");
        assertNotNull(lockedUntil(user));
        assertEquals(PinService.Outcome.LOCKED, pins.check(user, RIGHT, "transaction-service").outcome(),
                "the right PIN is refused while locked");
        assertEquals(51, auditRows(user), "every check is audited once: the fifty parallel ones, and the right PIN after them");
    }

    @Test
    void theLockDoublesEachTimeAndTopsOutAtADay() {
        assertEquals(Duration.ofMinutes(30), PinService.lockFor(1));
        assertEquals(Duration.ofMinutes(60), PinService.lockFor(2));
        assertEquals(Duration.ofMinutes(960), PinService.lockFor(6));
        assertEquals(Duration.ofHours(24), PinService.lockFor(7), "1920 minutes is capped at 24 hours");
        assertEquals(Duration.ofHours(24), PinService.lockFor(30));
    }

    @Test
    void aSecondLockAfterTheFirstHasRunOutLastsSixtyMinutes() {
        String user = aUserWithPin(RIGHT);
        jdbc.update("UPDATE pin_credential SET lockout_level = 1, locked_until = NULL, failed_count = 0 WHERE user_id = ?", user);

        for (int i = 0; i < 5; i++) {
            pins.check(user, WRONG, "transaction-service");
        }

        LocalDateTime until = lockedUntil(user);
        long minutes = Duration.between(LocalDateTime.now(), until).toMinutes();
        assertTrue(minutes >= 58 && minutes <= 60, "a second lock lasts about an hour, was " + minutes + " minutes");
        assertEquals(2, lockoutLevel(user));
    }

    @Test
    void aRightPinClearsTheFailuresAndTheLockLevel() {
        String user = aUserWithPin(RIGHT);
        jdbc.update("UPDATE pin_credential SET lockout_level = 2, failed_count = 3 WHERE user_id = ?", user);

        assertEquals(PinService.Outcome.VALID, pins.check(user, RIGHT, "transaction-service").outcome());

        assertEquals(0, failedCount(user));
        assertEquals(0, lockoutLevel(user));
    }

    @Test
    void aWrongPinBelowTheLimitCountsButDoesNotLock() {
        String user = aUserWithPin(RIGHT);

        for (int i = 0; i < 4; i++) {
            assertEquals(PinService.Outcome.WRONG, pins.check(user, WRONG, "transaction-service").outcome());
        }

        assertEquals(4, failedCount(user));
        assertEquals(null, lockedUntil(user), "four failures do not lock");
    }

    @Test
    void aUserWithNoPinIsToldSoWithoutAComparison() {
        String user = "user-" + UUID.randomUUID();

        assertEquals(PinService.Outcome.NOT_SET, pins.check(user, RIGHT, "transaction-service").outcome());
        assertEquals(1, auditRows(user, "PIN_NOT_SET"));
    }

    @Test
    void thePinIsStoredAsAHashNotAsDigits() {
        String user = aUserWithPin(RIGHT);

        String stored = credentials.findById(user).orElseThrow().getPinHash();

        assertFalse(stored.contains(RIGHT), "the stored value does not contain the PIN");
        assertTrue(hasher.matches(user, RIGHT, stored));
    }

    @Test
    void fiveWrongPasswordsOnAPinChangeLockThePin() {
        String user = aUserWithPin(RIGHT);

        for (int i = 0; i < 5; i++) {
            pins.recordPasswordFailure(user, "wallet-service");
        }

        assertEquals(PinService.Outcome.LOCKED, pins.check(user, RIGHT, "transaction-service").outcome(),
                "five wrong passwords lock the PIN, as five wrong PINs do (D-P3)");
    }

    // ------------------------------------------------------------------ helpers

    private String aUserWithPin(String pin) {
        String user = "user-" + UUID.randomUUID();
        credentials.saveAndFlush(new PinCredential(user, hasher.hash(user, pin)));
        return user;
    }

    private static long countOf(List<Object> outcomes, PinService.Outcome outcome) {
        return outcomes.stream().filter(o -> o instanceof PinService.Check c && c.outcome() == outcome).count();
    }

    private int failedCount(String user) {
        return jdbc.queryForObject("SELECT failed_count FROM pin_credential WHERE user_id = ?", Integer.class, user);
    }

    private int lockoutLevel(String user) {
        return jdbc.queryForObject("SELECT lockout_level FROM pin_credential WHERE user_id = ?", Integer.class, user);
    }

    private LocalDateTime lockedUntil(String user) {
        return jdbc.queryForObject("SELECT locked_until FROM pin_credential WHERE user_id = ?", LocalDateTime.class, user);
    }

    private int auditRows(String user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_type = 'PIN_CREDENTIAL' AND entity_id = ?",
                Integer.class, user);
    }

    private int auditRows(String user, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, user, action);
    }
}
