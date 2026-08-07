package com.example.subscription.repository;

import com.example.subscription.model.UserAccount;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryUserRepository {

    // key = username (email)
    private final ConcurrentHashMap<String, UserAccount> store = new ConcurrentHashMap<>();

    public UserAccount save(UserAccount account) {
        store.put(account.getUsername(), account);
        return account;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return Optional.ofNullable(store.get(username));
    }

    public boolean exists(String username) {
        return store.containsKey(username);
    }

    public void delete(String username) {
        store.remove(username);
    }

    public java.util.Collection<UserAccount> findAll() {
        return store.values();
    }
}
