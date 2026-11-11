package org.elibanking.paymentservice.payout;

/**
 * Converts the wallet's compact payout key ({@code out-{id}}) to a stable, zero-padded provider transfer reference and back
 * (wallet-plan 8.42, 9.5). The mapping is deterministic, so a resend uses the same reference and a webhook maps it back to the
 * internal key. Kept provider-neutral so any payout provider can reuse it.
 */
public final class PayoutReference {

    private static final int MINIMUM_DIGITS = 12;

    private PayoutReference() {
    }

    public static String fromPayoutKey(String key) {
        if (key == null || !key.matches("out-[1-9][0-9]*")) {
            throw new IllegalArgumentException("payout key must be out-{positive id}");
        }
        String id = key.substring(4);
        String reference = "out-" + "0".repeat(Math.max(0, MINIMUM_DIGITS - id.length())) + id;
        if (reference.length() > 50) {
            throw new IllegalArgumentException("payout id is too long for a provider reference");
        }
        return reference;
    }

    public static String toPayoutKey(String reference) {
        if (reference == null || !reference.matches("out-0*[1-9][0-9]*")) {
            throw new IllegalArgumentException("reference is not a payout reference");
        }
        return "out-" + reference.substring(4).replaceFirst("^0+", "");
    }
}
