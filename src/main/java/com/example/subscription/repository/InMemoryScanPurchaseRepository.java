package com.example.subscription.repository;

import com.example.subscription.model.ScanPurchase;
import com.example.subscription.model.ScanPurchaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InMemoryScanPurchaseRepository extends JpaRepository<ScanPurchase, String> {

    List<ScanPurchase> findByStatus(ScanPurchaseStatus status);

    List<ScanPurchase> findByEmail(String email);
}
