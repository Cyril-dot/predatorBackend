package com.example.subscription.repository;

import com.example.subscription.model.CommissionRecord;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class InMemoryCommissionRepository {

    // key = commission record id
    private final ConcurrentHashMap<String, CommissionRecord> store = new ConcurrentHashMap<>();

    public CommissionRecord save(CommissionRecord record) {
        store.put(record.getId(), record);
        return record;
    }

    public List<CommissionRecord> findByAdmin(String adminUsername) {
        return store.values().stream()
                .filter(c -> c.getAdminUsername().equals(adminUsername))
                .collect(Collectors.toList());
    }

    public List<CommissionRecord> findPendingByAdmin(String adminUsername) {
        return store.values().stream()
                .filter(c -> c.getAdminUsername().equals(adminUsername) && !c.isPaidOut())
                .collect(Collectors.toList());
    }

    public java.util.Collection<CommissionRecord> findAll() {
        return store.values();
    }
}
