package com.example.subscription.model;

import java.time.LocalDateTime;

/**
 * A batch payout event: super admin marks all of an admin's currently
 * pending commissions as paid in one go. This record is the receipt.
 */
public class PayoutRecord {

    private String id;
    private String adminUsername;
    private double totalAmountCedis;
    private int commissionCount;
    private LocalDateTime paidAt;
    private String paidByUsername; // which super admin triggered it

    public PayoutRecord(String id, String adminUsername, double totalAmountCedis, int commissionCount, String paidByUsername) {
        this.id = id;
        this.adminUsername = adminUsername;
        this.totalAmountCedis = totalAmountCedis;
        this.commissionCount = commissionCount;
        this.paidAt = LocalDateTime.now();
        this.paidByUsername = paidByUsername;
    }

    public String getId() {
        return id;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public double getTotalAmountCedis() {
        return totalAmountCedis;
    }

    public int getCommissionCount() {
        return commissionCount;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public String getPaidByUsername() {
        return paidByUsername;
    }
}
