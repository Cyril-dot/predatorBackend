package com.example.subscription.repository;

import com.example.subscription.model.ScanResult;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class InMemoryScanResultRepository {

    // key = scan result id
    private final ConcurrentHashMap<String, ScanResult> store = new ConcurrentHashMap<>();

    public ScanResult save(ScanResult result) {
        store.put(result.getId(), result);
        return result;
    }

    public Optional<ScanResult> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public Optional<ScanResult> findByPurchaseId(String purchaseId) {
        return store.values().stream()
                .filter(r -> r.getPurchaseId().equals(purchaseId))
                .findFirst();
    }

    public List<ScanResult> findByEmail(String email) {
        return store.values().stream()
                .filter(r -> r.getEmail().equals(email))
                .collect(Collectors.toList());
    }
}
