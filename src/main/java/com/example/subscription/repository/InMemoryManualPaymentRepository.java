package com.example.subscription.repository;

import com.example.subscription.model.ManualPayment;
import com.example.subscription.model.ManualPaymentStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class InMemoryManualPaymentRepository {

    // key = manual payment id
    private final ConcurrentHashMap<String, ManualPayment> store = new ConcurrentHashMap<>();

    public ManualPayment save(ManualPayment payment) {
        store.put(payment.getId(), payment);
        return payment;
    }

    public Optional<ManualPayment> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<ManualPayment> findAll() {
        return List.copyOf(store.values());
    }

    public List<ManualPayment> findByStatus(ManualPaymentStatus status) {
        return store.values().stream()
                .filter(p -> p.getStatus() == status)
                .collect(Collectors.toList());
    }

    public List<ManualPayment> findByEmail(String email) {
        return store.values().stream()
                .filter(p -> p.getEmail().equals(email))
                .collect(Collectors.toList());
    }
}
