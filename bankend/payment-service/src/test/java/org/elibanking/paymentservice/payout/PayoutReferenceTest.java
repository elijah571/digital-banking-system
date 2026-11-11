package org.elibanking.paymentservice.payout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayoutReferenceTest {

    @Test
    void padsThePayoutKeyToAStableReferenceAndBack() {
        assertEquals("out-000000000001", PayoutReference.fromPayoutKey("out-1"));
        assertEquals("out-000000012345", PayoutReference.fromPayoutKey("out-12345"));
        assertEquals("out-1", PayoutReference.toPayoutKey("out-000000000001"));
        assertEquals("out-12345", PayoutReference.toPayoutKey("out-000000012345"));
    }

    @Test
    void rejectsKeysAndReferencesOutsideThePayoutNamespace() {
        assertThrows(IllegalArgumentException.class, () -> PayoutReference.fromPayoutKey("out-0"));
        assertThrows(IllegalArgumentException.class, () -> PayoutReference.fromPayoutKey("tx-1"));
        assertThrows(IllegalArgumentException.class, () -> PayoutReference.fromPayoutKey(null));
        assertThrows(IllegalArgumentException.class, () -> PayoutReference.toPayoutKey("tx-000000000001"));
        assertThrows(IllegalArgumentException.class, () -> PayoutReference.toPayoutKey("out-000000000000"));
    }
}
