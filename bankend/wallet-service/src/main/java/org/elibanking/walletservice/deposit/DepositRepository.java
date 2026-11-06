package org.elibanking.walletservice.deposit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepositRepository extends JpaRepository<Deposit, String> {
}
