package org.elibanking.walletservice.payout;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The payout state table (wallet-plan 8.3), without a database. Every legal move is allowed, and every other move is refused. */
class OutboundPayoutStateMachineTest {

    @Test
    void theHappyPathIsAllowed() {
        assertTrue(OutboundPayoutStateMachine.allowed(PayoutState.REQUESTED, PayoutState.APPROVED));
        assertTrue(OutboundPayoutStateMachine.allowed(PayoutState.APPROVED, PayoutState.PROCESSING));
        assertTrue(OutboundPayoutStateMachine.allowed(PayoutState.PROCESSING, PayoutState.COMPLETED));
    }

    @Test
    void everyTransitionMatchesTheClosedStateTable() {
        Map<PayoutState, Set<PayoutState>> legal = Map.of(
                PayoutState.REQUESTED, Set.of(PayoutState.APPROVED, PayoutState.PENDING_APPROVAL, PayoutState.FAILED),
                PayoutState.PENDING_APPROVAL, Set.of(PayoutState.APPROVED, PayoutState.REJECTED, PayoutState.CANCELLED,
                        PayoutState.EXPIRED),
                PayoutState.APPROVED, Set.of(PayoutState.PROCESSING),
                PayoutState.PROCESSING, Set.of(PayoutState.COMPLETED, PayoutState.FAILED, PayoutState.REVERSED),
                PayoutState.COMPLETED, Set.of(PayoutState.REVERSED));

        for (PayoutState from : PayoutState.values()) {
            for (PayoutState to : PayoutState.values()) {
                boolean expected = legal.getOrDefault(from, Set.of()).contains(to);
                assertEquals(expected, OutboundPayoutStateMachine.allowed(from, to), from + " -> " + to);
                if (!expected) {
                    assertThrows(IllegalStateException.class, () -> OutboundPayoutStateMachine.require(from, to),
                            from + " -> " + to + " must be refused");
                }
            }
        }
    }

    @Test
    void aReversalIsAllowedFromProcessingAndFromCompleted() {
        assertTrue(OutboundPayoutStateMachine.allowed(PayoutState.PROCESSING, PayoutState.REVERSED), "release only, nothing was settled");
        assertTrue(OutboundPayoutStateMachine.allowed(PayoutState.COMPLETED, PayoutState.REVERSED), "the reversal entry");
    }

    @Test
    void aSettledPayoutCannotBeSentAgain() {
        assertFalse(OutboundPayoutStateMachine.allowed(PayoutState.COMPLETED, PayoutState.PROCESSING));
        assertThrows(IllegalStateException.class,
                () -> OutboundPayoutStateMachine.require(PayoutState.COMPLETED, PayoutState.PROCESSING));
    }

    @Test
    void theEndStatesHaveNoMovesOut() {
        for (PayoutState end : new PayoutState[] {PayoutState.FAILED, PayoutState.REJECTED, PayoutState.CANCELLED,
                PayoutState.EXPIRED, PayoutState.REVERSED}) {
            for (PayoutState to : PayoutState.values()) {
                assertFalse(OutboundPayoutStateMachine.allowed(end, to), end + " -> " + to + " must be refused");
            }
        }
    }

    @Test
    void aPendingApprovalCanBeApprovedRejectedCancelledOrExpired() {
        for (PayoutState to : new PayoutState[] {PayoutState.APPROVED, PayoutState.REJECTED, PayoutState.CANCELLED,
                PayoutState.EXPIRED}) {
            assertTrue(OutboundPayoutStateMachine.allowed(PayoutState.PENDING_APPROVAL, to));
        }
        assertFalse(OutboundPayoutStateMachine.allowed(PayoutState.PENDING_APPROVAL, PayoutState.PROCESSING),
                "an approval is a move to APPROVED first, then to PROCESSING");
    }

    @Test
    void theGiveBackStatesAreTheOnesThatEndWithoutASettle() {
        assertTrue(OutboundPayoutStateMachine.GIVE_BACK.contains(PayoutState.FAILED));
        assertTrue(OutboundPayoutStateMachine.GIVE_BACK.contains(PayoutState.REJECTED));
        assertTrue(OutboundPayoutStateMachine.GIVE_BACK.contains(PayoutState.CANCELLED));
        assertTrue(OutboundPayoutStateMachine.GIVE_BACK.contains(PayoutState.EXPIRED));
        assertTrue(OutboundPayoutStateMachine.GIVE_BACK.contains(PayoutState.REVERSED), "D21: a reversed payout gives its allowance back");
        assertFalse(OutboundPayoutStateMachine.GIVE_BACK.contains(PayoutState.COMPLETED));
        assertFalse(OutboundPayoutStateMachine.GIVE_BACK.contains(PayoutState.PROCESSING));
    }
}
