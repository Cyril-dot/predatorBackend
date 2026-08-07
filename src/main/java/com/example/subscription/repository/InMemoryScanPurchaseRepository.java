package com.example.subscription.repository;

import com.example.subscription.model.ScanPurchase;
import com.example.subscription.model.ScanPurchaseStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class InMemoryScanPurchaseRepository {

    // key = scan purchase id
    private final ConcurrentHashMap<String, ScanPurchase> store = new ConcurrentHashMap<>();

    public ScanPurchase save(ScanPurchase purchase) {
        store.put(purchase.getId(), purchase);
        return purchase;
    }

    public Optional<ScanPurchase> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<ScanPurchase> findAll() {
        return List.copyOf(store.values());
    }

    public List<ScanPurchase> findByStatus(ScanPurchaseStatus status) {
        return store.values().stream()
                .filter(p -> p.getStatus() == status)
                .collect(Collectors.toList());
    }

    public List<ScanPurchase> findByEmail(String email) {
        return store.values().stream()
                .filter(p -> p.getEmail().equals(email))
                .collect(Collectors.toList());
    }
}
