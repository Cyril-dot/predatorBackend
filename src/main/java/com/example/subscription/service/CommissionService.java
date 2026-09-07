package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.*;
import com.example.subscription.repository.InMemoryAdminRepository;
import com.example.subscription.repository.InMemoryCommissionRepository;
import com.example.subscription.repository.InMemoryPayoutRepository;
import com.example.subscription.util.CodeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CommissionService {

    private static final Logger log = LoggerFactory.getLogger(CommissionService.class);

    private final InMemoryAdminRepository adminRepository;
    private final InMemoryCommissionRepository commissionRepository;
    private final InMemoryPayoutRepository payoutRepository;

    public CommissionService(InMemoryAdminRepository adminRepository,
                              InMemoryCommissionRepository commissionRepository,
                              InMemoryPayoutRepository payoutRepository) {
        this.adminRepository = adminRepository;
        this.commissionRepository = commissionRepository;
        this.payoutRepository = payoutRepository;
    }

    /**
     * Called right after a payment is verified as successful. If the paying
     * user was referred by an admin's referral code (and that admin is still
     * active), a CommissionRecord is created splitting the payment between
     * the admin and the platform. No-op if there's no valid referral.
     */
    public void recordIfReferred(String paymentReference, String userEmail, Plan plan,
                                  int amountCedis, String referralCode) {
        if (referralCode == null || referralCode.isBlank()) {
            return;
        }

        Admin admin = adminRepository.findByReferralCode(referralCode).orElse(null);
        if (admin == null || !admin.isActive()) {
            log.warn("Referral code '{}' on payment {} did not match an active admin - no commission recorded",
                    referralCode, paymentReference);
            return;
        }

        double adminShare = amountCedis * (admin.getAdminSharePercent() / 100.0);
        double platformShare = amountCedis - adminShare;

        CommissionRecord record = new CommissionRecord(
                CodeGenerator.generateId(), paymentReference, admin.getUsername(), userEmail,
                plan, amountCedis, adminShare, platformShare);

        commissionRepository.save(record);
    }

    public List<CommissionRecord> listForAdmin(String adminUsername) {
        return commissionRepository.findByAdmin(adminUsername);
    }

    public List<CommissionRecord> listAll() {
        return List.copyOf(commissionRepository.findAll());
    }

    /** Sums an admin's commission earnings for a given local date (based on when the commission was created). */
    public double earnedOn(String adminUsername, LocalDate date) {
        return commissionRepository.findByAdmin(adminUsername).stream()
                .filter(c -> c.getCreatedAt().toLocalDate().equals(date))
                .mapToDouble(CommissionRecord::getAdminShareCedis)
                .sum();
    }

    /** Sums an admin's commission earnings for the last `days` days (inclusive of today). */
    public double earnedSince(String adminUsername, LocalDateTime since) {
        return commissionRepository.findByAdmin(adminUsername).stream()
                .filter(c -> !c.getCreatedAt().isBefore(since))
                .mapToDouble(CommissionRecord::getAdminShareCedis)
                .sum();
    }

    public double totalEarned(String adminUsername) {
        return commissionRepository.findByAdmin(adminUsername).stream()
                .mapToDouble(CommissionRecord::getAdminShareCedis)
                .sum();
    }

    public double totalPending(String adminUsername) {
        return commissionRepository.findPendingByAdmin(adminUsername).stream()
                .mapToDouble(CommissionRecord::getAdminShareCedis)
                .sum();
    }

    public double totalPaidOut(String adminUsername) {
        return commissionRepository.findByAdmin(adminUsername).stream()
                .filter(CommissionRecord::isPaidOut)
                .mapToDouble(CommissionRecord::getAdminShareCedis)
                .sum();
    }

    /**
     * Super-admin action: pays out ALL of an admin's currently pending
     * commissions in one batch, and returns the receipt (PayoutRecord).
     */
    public synchronized PayoutRecord payoutAdmin(String adminUsername, String paidByUsername) {
        List<CommissionRecord> pending = commissionRepository.findPendingByAdmin(adminUsername);
        if (pending.isEmpty()) {
            throw new ApiException("No pending commission to pay out for " + adminUsername, HttpStatus.BAD_REQUEST);
        }

        double total = pending.stream().mapToDouble(CommissionRecord::getAdminShareCedis).sum();
        String payoutId = CodeGenerator.generateId();

        PayoutRecord payoutRecord = new PayoutRecord(payoutId, adminUsername, total, pending.size(), paidByUsername);
        payoutRepository.save(payoutRecord);

        LocalDateTime now = LocalDateTime.now();
        for (CommissionRecord c : pending) {
            c.setPaidOut(true);
            c.setPaidOutAt(now);
            c.setPayoutId(payoutId);
            commissionRepository.save(c);
        }

        return payoutRecord;
    }

    public List<PayoutRecord> payoutsForAdmin(String adminUsername) {
        return payoutRepository.findByAdmin(adminUsername);
    }

    public List<PayoutRecord> allPayouts() {
        return List.copyOf(payoutRepository.findAll());
    }
}
