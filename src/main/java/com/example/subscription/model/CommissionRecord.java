package com.example.subscription.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Created once, at the moment a referred user's payment is verified -
 * whether that's a time-based plan (2HR/3HR/5HR) or an AI scan purchase
 * (BASIC/STANDARD/PREMIUM). Records the 70/30 (or whatever the admin's
 * split is) breakdown of that single payment.
 *
 * planLabel is a plain string (e.g. "THREE_HOUR" or "SCAN_BASIC") rather
 * than a typed enum, since commissions can come from either the time-plan
 * ({@link Plan}) or the scan-plan ({@link ScanPlan}) product lines.
 */
@Entity
@Table(name = "commission_records")
public class CommissionRecord {

    @Id
    private String id;

    private String paymentReference;
    private String adminUsername;
    private String referredUserEmail;

    private String planLabel; // e.g. "THREE_HOUR" or "SCAN_BASIC"

    private int amountCedis;        // total amount paid
    private double adminShareCedis; // admin's cut
    private double platformShareCedis; // platform's cut
    private LocalDateTime createdAt;
    private boolean paidOut;
    private LocalDateTime paidOutAt;
    private String payoutId;

    protected CommissionRecord() {
    }

    public CommissionRecord(String id, String paymentReference, String adminUsername, String referredUserEmail,
                             String planLabel, int amountCedis, double adminShareCedis, double platformShareCedis) {
        this.id = id;
        this.paymentReference = paymentReference;
        this.adminUsername = adminUsername;
        this.referredUserEmail = referredUserEmail;
        this.planLabel = planLabel;
        this.amountCedis = amountCedis;
        this.adminShareCedis = adminShareCedis;
        this.platformShareCedis = platformShareCedis;
        this.createdAt = LocalDateTime.now();
        this.paidOut = false;
    }

    public String getId() {
        return id;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public String getReferredUserEmail() {
        return referredUserEmail;
    }

    public String getPlanLabel() {
        return planLabel;
    }

    public int getAmountCedis() {
        return amountCedis;
    }

    public double getAdminShareCedis() {
        return adminShareCedis;
    }

    public double getPlatformShareCedis() {
        return platformShareCedis;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isPaidOut() {
        return paidOut;
    }

    public void setPaidOut(boolean paidOut) {
        this.paidOut = paidOut;
    }

    public LocalDateTime getPaidOutAt() {
        return paidOutAt;
    }

    public void setPaidOutAt(LocalDateTime paidOutAt) {
        this.paidOutAt = paidOutAt;
    }

    public String getPayoutId() {
        return payoutId;
    }

    public void setPayoutId(String payoutId) {
        this.payoutId = payoutId;
    }
}
