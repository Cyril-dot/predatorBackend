package com.example.subscription.repository;

import com.example.subscription.model.Session;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemorySessionRepository {

    // key = token
    private final ConcurrentHashMap<String, Session> store = new ConcurrentHashMap<>();

    public Session save(Session session) {
        store.put(session.getToken(), session);
        return session;
    }

    public Optional<Session> findByToken(String token) {
        return Optional.ofNullable(store.get(token));
    }

    public void deleteByToken(String token) {
        store.remove(token);
    }

    public java.util.Collection<Session> findAll() {
        return store.values();
    }

    public Optional<Session> findActiveByUsername(String username) {
        return store.values().stream()
                .filter(s -> s.getUsername().equals(username) && s.isActive())
                .findFirst();
    }
}
