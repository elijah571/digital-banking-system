package org.elibanking.walletservice.payout;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboundPayoutRepository extends JpaRepository<OutboundPayout, Long> {

    Page<OutboundPayout> findAllByStateOrderByCreatedAtDesc(PayoutState state, Pageable page);

    /** The ids of payouts in this state since the cutoff, oldest first. The state's own time is the update time (8.3). */
    @Query("select p.id from OutboundPayout p where p.state = :state and p.updatedAt < :cutoff order by p.updatedAt")
    List<Long> findIdsInStateSince(@Param("state") PayoutState state, @Param("cutoff") Instant cutoff, Pageable page);

    /** The ids of payouts in any of these states, oldest update first. The processor works through them. */
    @Query("select p.id from OutboundPayout p where p.state in :states order by p.updatedAt")
    List<Long> findIdsInStates(@Param("states") List<PayoutState> states, Pageable page);
}
