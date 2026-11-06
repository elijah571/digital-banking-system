package org.elibanking.walletservice.deposit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DepositRepository extends JpaRepository<Deposit, String> {

    /** The ids of deposits in this status whose time has run out, oldest first (wallet-plan 3.5, step 6). */
    @Query("select d.id from Deposit d where d.status = :status and d.expiresAt < :now order by d.expiresAt")
    List<String> findIdsExpiredBy(@Param("status") DepositStatus status, @Param("now") Instant now, Pageable page);
}
