package com.example.subscription.repository;

import com.example.subscription.model.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InMemorySessionRepository extends JpaRepository<Session, String> {

    Optional<Session> findByToken(String token);

    Optional<Session> findFirstByUsernameAndActiveTrue(String username);

    default void deleteByToken(String token) {
        deleteById(token);
    }

    default Optional<Session> findActiveByUsername(String username) {
        return findFirstByUsernameAndActiveTrue(username);
    }
}
