package com.example.subscription.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A manual payment submission for an AI betting-slip scan (see
 * {@link ScanPlan}). Mirrors {@link ManualPayment}'s review flow, but instead
 * of unlocking a password/time-window, an APPROVED purchase unlocks exactly
 * one call to POST /api/scan/analyze.
 *
 * Lifecycle: PENDING -> APPROVED -> USED
 *                     -> REJECTED
 */
@Entity
@Table(name = "scan_purchases")
public class ScanPurchase {

    @Id
    private String id;

    private String email;

    @Enumerated(EnumType.STRING)
    private ScanPlan scanPlan;

    // Proof of payment, same shape as ManualPayment
    private String accountName;
    private String accountNumber;
    private String networkOrBank;
    private String reference;
    private String screenshotUrl;

    @Enumerated(EnumType.STRING)
    private ScanPurchaseStatus status;

    private String rejectionReason;

    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
    private String reviewedByAdmin;
    private LocalDateTime usedAt;

    /**
     * The referral code (if any) attached to this email's account at the
     * time of submission - captured here so approving the purchase can
     * record admin commission, same as time-plan payments.
     */
    private String referredByAdminCode;

    protected ScanPurchase() {
    }

    public ScanPurchase(String id, String email, ScanPlan scanPlan, String accountName, String accountNumber,
                        String networkOrBank, String reference, String screenshotUrl) {
        this.id = id;
        this.email = email;
        this.scanPlan = scanPlan;
        this.accountName = accountName;
        this.accountNumber = accountNumber;
        this.networkOrBank = networkOrBank;
        this.reference = reference;
        this.screenshotUrl = screenshotUrl;
        this.status = ScanPurchaseStatus.PENDING;
        this.submittedAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public ScanPlan getScanPlan() {
        return scanPlan;
    }

    public String getAccountName() {
        return accountName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getNetworkOrBank() {
        return networkOrBank;
    }

    public String getReference() {
        return reference;
    }

    public String getScreenshotUrl() {
        return screenshotUrl;
    }

    public ScanPurchaseStatus getStatus() {
        return status;
    }

    public void setStatus(ScanPurchaseStatus status) {
        this.status = status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewedByAdmin() {
        return reviewedByAdmin;
    }

    public void setReviewedByAdmin(String reviewedByAdmin) {
        this.reviewedByAdmin = reviewedByAdmin;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
    }

    public boolean isReadyToScan() {
        return status == ScanPurchaseStatus.APPROVED;
    }

    public String getReferredByAdminCode() {
        return referredByAdminCode;
    }

    public void setReferredByAdminCode(String referredByAdminCode) {
        this.referredByAdminCode = referredByAdminCode;
    }
}
