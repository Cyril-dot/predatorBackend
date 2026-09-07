package com.example.subscription.repository;

import com.example.subscription.model.CommissionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InMemoryCommissionRepository extends JpaRepository<CommissionRecord, String> {

    List<CommissionRecord> findByAdminUsername(String adminUsername);

    List<CommissionRecord> findByAdminUsernameAndPaidOutFalse(String adminUsername);

    default List<CommissionRecord> findByAdmin(String adminUsername) {
        return findByAdminUsername(adminUsername);
    }

    default List<CommissionRecord> findPendingByAdmin(String adminUsername) {
        return findByAdminUsernameAndPaidOutFalse(adminUsername);
    }
}
