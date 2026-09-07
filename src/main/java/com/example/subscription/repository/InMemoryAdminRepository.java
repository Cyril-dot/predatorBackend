package com.example.subscription.repository;

import com.example.subscription.model.Admin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InMemoryAdminRepository extends JpaRepository<Admin, String> {

    Optional<Admin> findByUsername(String username);

    Optional<Admin> findByReferralCode(String referralCode);

    boolean existsByReferralCode(String referralCode);

    default boolean referralCodeExists(String code) {
        return existsByReferralCode(code);
    }

    default boolean exists(String username) {
        return existsById(username);
    }
}
