package com.example.subscription.repository;

import com.example.subscription.model.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InMemoryPaymentRepository extends JpaRepository<PaymentTransaction, String> {

    Optional<PaymentTransaction> findByReference(String reference);
}
