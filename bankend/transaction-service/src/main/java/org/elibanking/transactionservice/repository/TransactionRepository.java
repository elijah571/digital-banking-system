package org.elibanking.transactionservice.repository;

import org.elibanking.transactionservice.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findBySenderAccountNumberOrderByCreatedAtDesc(String accountNumber);

    List<Transaction> findTop50ByStatusAndHeldAtIsNullAndCreatedAtBefore(
            org.elibanking.transactionservice.entity.TransactionStatus status, java.time.LocalDateTime cutoff);

    List<Transaction> findAllByOrderByCreatedAtDesc();

}
