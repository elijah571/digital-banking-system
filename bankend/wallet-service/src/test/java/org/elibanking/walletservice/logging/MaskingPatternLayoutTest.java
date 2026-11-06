package org.elibanking.walletservice.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The console layout masks PINs, OTPs and passwords in both common forms (threat model T4). Plain, no context. */
class MaskingPatternLayoutTest {

    @Test
    void masksSecretsInJsonForm() {
        assertEquals("{\"pin\":\"****\"}", MaskingPatternLayout.mask("{\"pin\":\"1234\"}"));
        assertEquals("{\"otp\":\"****\", \"userId\":\"u1\"}", MaskingPatternLayout.mask("{\"otp\":\"567890\", \"userId\":\"u1\"}"));
        assertEquals("{\"password\":\"****\"}", MaskingPatternLayout.mask("{\"password\":\"hunter2\"}"));
    }

    @Test
    void masksSecretsInKeyValueForm() {
        assertEquals("pin=****, otp=****", MaskingPatternLayout.mask("pin=1234, otp=567890"));
        assertEquals("currentPassword=****", MaskingPatternLayout.mask("currentPassword=hunter2"));
    }

    @Test
    void leavesOtherWordsAlone() {
        assertEquals("pinned: yes, opinion=ok", MaskingPatternLayout.mask("pinned: yes, opinion=ok"));
        assertEquals("the account is 3693612260", MaskingPatternLayout.mask("the account is 3693612260"));
    }
}
