package org.elibanking.paymentservice.payout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaystackReferenceTest {

    @Test
    void padsShortIdsAndRoundTripsProviderReferences() {
        String reference = PaystackReference.fromPayoutKey("out-1");
        assertEquals("out-000000000001", reference);
        assertEquals("out-1", PaystackReference.toPayoutKey(reference));
        assertEquals("out-1234567890123456789", PaystackReference.fromPayoutKey("out-1234567890123456789"));
    }

    @Test
    void rejectsReferencesOutsideThePayoutNamespace() {
        assertThrows(IllegalArgumentException.class, () -> PaystackReference.fromPayoutKey("out-0"));
        assertThrows(IllegalArgumentException.class, () -> PaystackReference.fromPayoutKey("tx-1"));
        assertThrows(IllegalArgumentException.class, () -> PaystackReference.toPayoutKey("out-0000"));
    }
}
