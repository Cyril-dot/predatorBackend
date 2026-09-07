package com.example.subscription.repository;

import com.example.subscription.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Despite the name (kept for compatibility with the rest of the codebase),
 * this is now backed by a real database (Neon/Postgres) via Spring Data JPA,
 * not an in-memory map.
 */
@Repository
public interface InMemoryUserRepository extends JpaRepository<UserAccount, String> {

    Optional<UserAccount> findByUsername(String username);

    default boolean exists(String username) {
        return existsById(username);
    }

    default void delete(String username) {
        deleteById(username);
    }
}
