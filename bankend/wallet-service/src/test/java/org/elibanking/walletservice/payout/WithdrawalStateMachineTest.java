package org.elibanking.walletservice.payout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The payout state table (wallet-plan 8.3), without a database. Every legal move is allowed, and every other move is refused. */
class WithdrawalStateMachineTest {

    @Test
    void theHappyPathIsAllowed() {
        assertTrue(WithdrawalStateMachine.allowed(PayoutState.REQUESTED, PayoutState.APPROVED));
        assertTrue(WithdrawalStateMachine.allowed(PayoutState.APPROVED, PayoutState.PROCESSING));
        assertTrue(WithdrawalStateMachine.allowed(PayoutState.PROCESSING, PayoutState.COMPLETED));
    }

    @Test
    void aReversalIsAllowedFromProcessingAndFromCompleted() {
        assertTrue(WithdrawalStateMachine.allowed(PayoutState.PROCESSING, PayoutState.REVERSED), "release only, nothing was settled");
        assertTrue(WithdrawalStateMachine.allowed(PayoutState.COMPLETED, PayoutState.REVERSED), "the reversal entry");
    }

    @Test
    void aSettledPayoutCannotBeSentAgain() {
        assertFalse(WithdrawalStateMachine.allowed(PayoutState.COMPLETED, PayoutState.PROCESSING));
        assertThrows(IllegalStateException.class,
                () -> WithdrawalStateMachine.require(PayoutState.COMPLETED, PayoutState.PROCESSING));
    }

    @Test
    void theEndStatesHaveNoMovesOut() {
        for (PayoutState end : new PayoutState[] {PayoutState.FAILED, PayoutState.REJECTED, PayoutState.CANCELLED,
                PayoutState.EXPIRED, PayoutState.REVERSED}) {
            for (PayoutState to : PayoutState.values()) {
                if (end == PayoutState.REVERSED && to == PayoutState.REVERSED) {
                    continue;
                }
                assertFalse(WithdrawalStateMachine.allowed(end, to), end + " -> " + to + " must be refused");
            }
        }
    }

    @Test
    void aPendingApprovalCanBeApprovedRejectedCancelledOrExpired() {
        for (PayoutState to : new PayoutState[] {PayoutState.APPROVED, PayoutState.REJECTED, PayoutState.CANCELLED,
                PayoutState.EXPIRED}) {
            assertTrue(WithdrawalStateMachine.allowed(PayoutState.PENDING_APPROVAL, to));
        }
        assertFalse(WithdrawalStateMachine.allowed(PayoutState.PENDING_APPROVAL, PayoutState.PROCESSING),
                "an approval is a move to APPROVED first, then to PROCESSING");
    }

    @Test
    void theGiveBackStatesAreTheOnesThatEndWithoutASettle() {
        assertTrue(WithdrawalStateMachine.GIVE_BACK.contains(PayoutState.FAILED));
        assertTrue(WithdrawalStateMachine.GIVE_BACK.contains(PayoutState.REJECTED));
        assertTrue(WithdrawalStateMachine.GIVE_BACK.contains(PayoutState.CANCELLED));
        assertTrue(WithdrawalStateMachine.GIVE_BACK.contains(PayoutState.EXPIRED));
        assertTrue(WithdrawalStateMachine.GIVE_BACK.contains(PayoutState.REVERSED), "D21: a reversed payout gives its allowance back");
        assertFalse(WithdrawalStateMachine.GIVE_BACK.contains(PayoutState.COMPLETED));
        assertFalse(WithdrawalStateMachine.GIVE_BACK.contains(PayoutState.PROCESSING));
    }
}
