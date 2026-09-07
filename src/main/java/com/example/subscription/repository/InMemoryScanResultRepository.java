package com.example.subscription.repository;

import com.example.subscription.model.ScanResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InMemoryScanResultRepository extends JpaRepository<ScanResult, String> {

    Optional<ScanResult> findByPurchaseId(String purchaseId);

    List<ScanResult> findByEmail(String email);
}
