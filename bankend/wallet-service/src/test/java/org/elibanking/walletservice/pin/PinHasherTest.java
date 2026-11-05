package org.elibanking.walletservice.pin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stored PIN hash (threat model T3, D-P7). Plain objects, no context. */
class PinHasherTest {

    private static final String PEPPER = "a-pepper-of-at-least-thirty-two-bytes-long";
    private final PinHasher hasher = new PinHasher(PEPPER);

    @Test
    void aHashVerifiesOnlyForItsOwnUser() {
        String hash = hasher.hash("alice", "1234");

        assertTrue(hasher.matches("alice", "1234", hash));
        assertFalse(hasher.matches("bob", "1234", hash), "the user id is part of the keyed input");
        assertFalse(hasher.matches("alice", "4321", hash));
    }

    @Test
    void twoUsersWithTheSamePinGetDifferentHashes() {
        assertNotEquals(hasher.hash("alice", "1234"), hasher.hash("bob", "1234"));
    }

    @Test
    void aHashDoesNotVerifyWithAnotherPepper() {
        String hash = hasher.hash("alice", "1234");

        assertFalse(new PinHasher("another-pepper-of-at-least-thirty-two-bytes").matches("alice", "1234", hash),
                "a stolen table is not enough without the pepper");
    }

    @Test
    void theKeyedInputDependsOnTheUserAndThePin() {
        assertNotEquals(hasher.keyed("alice", "1234"), hasher.keyed("bob", "1234"));
        assertNotEquals(hasher.keyed("alice", "1234"), hasher.keyed("alice", "4321"));
    }
}
