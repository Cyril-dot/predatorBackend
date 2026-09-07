package com.example.subscription.repository;

import com.example.subscription.model.PayoutRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InMemoryPayoutRepository extends JpaRepository<PayoutRecord, String> {

    List<PayoutRecord> findByAdminUsername(String adminUsername);

    default List<PayoutRecord> findByAdmin(String adminUsername) {
        return findByAdminUsername(adminUsername);
    }
}
