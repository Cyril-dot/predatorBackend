package com.example.subscription.repository;

import com.example.subscription.model.AdminSession;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryAdminSessionRepository {

    // key = token
    private final ConcurrentHashMap<String, AdminSession> store = new ConcurrentHashMap<>();

    public AdminSession save(AdminSession session) {
        store.put(session.getToken(), session);
        return session;
    }

    public Optional<AdminSession> findByToken(String token) {
        return Optional.ofNullable(store.get(token));
    }

    public void deleteByToken(String token) {
        store.remove(token);
    }
}
