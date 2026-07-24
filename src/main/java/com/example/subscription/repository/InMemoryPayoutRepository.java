package com.example.subscription.repository;

import com.example.subscription.model.PayoutRecord;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class InMemoryPayoutRepository {

    // key = payout id
    private final ConcurrentHashMap<String, PayoutRecord> store = new ConcurrentHashMap<>();

    public PayoutRecord save(PayoutRecord record) {
        store.put(record.getId(), record);
        return record;
    }

    public List<PayoutRecord> findByAdmin(String adminUsername) {
        return store.values().stream()
                .filter(p -> p.getAdminUsername().equals(adminUsername))
                .collect(Collectors.toList());
    }

    public java.util.Collection<PayoutRecord> findAll() {
        return store.values();
    }
}
