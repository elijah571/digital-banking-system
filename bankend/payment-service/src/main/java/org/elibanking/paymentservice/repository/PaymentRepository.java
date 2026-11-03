package org.elibanking.paymentservice.repository;

import org.elibanking.paymentservice.entity.Payment; // Fixed: Explicitly imported correct entity class
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, String> {
    Optional<Payment> findByStripePaymentIntentId(String paymentIntentId);
}
