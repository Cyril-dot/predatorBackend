package com.example.subscription.repository;

import com.example.subscription.model.AdminSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InMemoryAdminSessionRepository extends JpaRepository<AdminSession, String> {

    Optional<AdminSession> findByToken(String token);

    default void deleteByToken(String token) {
        deleteById(token);
    }
}
