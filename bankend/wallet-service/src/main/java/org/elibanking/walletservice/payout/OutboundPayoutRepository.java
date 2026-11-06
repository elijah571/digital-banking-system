package org.elibanking.walletservice.payout;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboundPayoutRepository extends JpaRepository<OutboundPayout, Long> {
}
