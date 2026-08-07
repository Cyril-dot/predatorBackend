package com.example.subscription.repository;

import com.example.subscription.model.Admin;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryAdminRepository {

    // key = username (email)
    private final ConcurrentHashMap<String, Admin> store = new ConcurrentHashMap<>();
    // key = referralCode -> username, for fast lookup at registration time
    private final ConcurrentHashMap<String, String> codeToUsername = new ConcurrentHashMap<>();

    public Admin save(Admin admin) {
        store.put(admin.getUsername(), admin);
        codeToUsername.put(admin.getReferralCode(), admin.getUsername());
        return admin;
    }

    public Optional<Admin> findByUsername(String username) {
        return Optional.ofNullable(store.get(username));
    }

    public Optional<Admin> findByReferralCode(String code) {
        String username = codeToUsername.get(code);
        if (username == null) {
            return Optional.empty();
        }
        return findByUsername(username);
    }

    public boolean referralCodeExists(String code) {
        return codeToUsername.containsKey(code);
    }

    public boolean exists(String username) {
        return store.containsKey(username);
    }

    public java.util.Collection<Admin> findAll() {
        return store.values();
    }
}
