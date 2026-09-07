package com.example.subscription.repository;

import com.example.subscription.model.ManualPayment;
import com.example.subscription.model.ManualPaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InMemoryManualPaymentRepository extends JpaRepository<ManualPayment, String> {

    List<ManualPayment> findByStatus(ManualPaymentStatus status);

    List<ManualPayment> findByEmail(String email);
}
