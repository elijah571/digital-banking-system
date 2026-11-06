package org.elibanking.walletservice.payout;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The legal moves of an outbound payout (wallet-plan 8.3). One table, so every change goes through the same check. A move that is not
 * in the table is refused. REVERSED is reached only from PROCESSING (release only, nothing was settled) or from COMPLETED (the
 * reversal entry). The states in {@link #GIVE_BACK} end the payout without a settle, so the daily usage is given back (fix 5, D21).
 */
public final class WithdrawalStateMachine {

    private static final Map<PayoutState, Set<PayoutState>> LEGAL = new EnumMap<>(PayoutState.class);

    /** The states that give the daily usage back. REVERSED is one of them (D21). */
    public static final Set<PayoutState> GIVE_BACK = EnumSet.of(PayoutState.FAILED, PayoutState.REJECTED, PayoutState.CANCELLED,
            PayoutState.EXPIRED, PayoutState.REVERSED);

    static {
        LEGAL.put(PayoutState.REQUESTED, EnumSet.of(PayoutState.APPROVED, PayoutState.PENDING_APPROVAL, PayoutState.FAILED));
        LEGAL.put(PayoutState.PENDING_APPROVAL, EnumSet.of(PayoutState.APPROVED, PayoutState.REJECTED, PayoutState.CANCELLED,
                PayoutState.EXPIRED));
        LEGAL.put(PayoutState.APPROVED, EnumSet.of(PayoutState.PROCESSING));
        LEGAL.put(PayoutState.PROCESSING, EnumSet.of(PayoutState.COMPLETED, PayoutState.FAILED, PayoutState.REVERSED));
        LEGAL.put(PayoutState.COMPLETED, EnumSet.of(PayoutState.REVERSED));
        // REJECTED, CANCELLED, EXPIRED, FAILED and REVERSED end the payout: they have no moves out.
    }

    private WithdrawalStateMachine() {
    }

    public static boolean allowed(PayoutState from, PayoutState to) {
        return LEGAL.getOrDefault(from, EnumSet.noneOf(PayoutState.class)).contains(to);
    }

    /** Refuses a move that is not in the table. */
    public static void require(PayoutState from, PayoutState to) {
        if (!allowed(from, to)) {
            throw new IllegalStateException("a payout cannot move from " + from + " to " + to);
        }
    }
}
