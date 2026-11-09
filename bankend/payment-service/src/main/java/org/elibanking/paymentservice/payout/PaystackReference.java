package org.elibanking.paymentservice.payout;

/** Converts the wallet's compact payout key to Paystack's 16–50 character transfer reference. */
public final class PaystackReference {

    private static final int MINIMUM_DIGITS = 12;

    private PaystackReference() {
    }

    public static String fromPayoutKey(String key) {
        if (key == null || !key.matches("out-[1-9][0-9]*")) {
            throw new IllegalArgumentException("payout key must be out-{positive id}");
        }
        String id = key.substring(4);
        String reference = "out-" + "0".repeat(Math.max(0, MINIMUM_DIGITS - id.length())) + id;
        if (reference.length() > 50) {
            throw new IllegalArgumentException("payout id is too long for a Paystack reference");
        }
        return reference;
    }

    public static String toPayoutKey(String reference) {
        if (reference == null || !reference.matches("out-0*[1-9][0-9]*")) {
            throw new IllegalArgumentException("Paystack reference is not a payout reference");
        }
        return "out-" + reference.substring(4).replaceFirst("^0+", "");
    }
}
