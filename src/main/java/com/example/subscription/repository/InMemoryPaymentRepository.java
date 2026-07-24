package com.example.subscription.repository;

import com.example.subscription.model.PaymentTransaction;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryPaymentRepository {

    // key = paystack reference
    private final ConcurrentHashMap<String, PaymentTransaction> store = new ConcurrentHashMap<>();

    public PaymentTransaction save(PaymentTransaction tx) {
        store.put(tx.getReference(), tx);
        return tx;
    }

    public Optional<PaymentTransaction> findByReference(String reference) {
        return Optional.ofNullable(store.get(reference));
    }

    public java.util.Collection<PaymentTransaction> findAll() {
        return store.values();
    }
}
