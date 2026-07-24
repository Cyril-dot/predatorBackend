package com.example.subscription.model;

import java.time.LocalDateTime;

/**
 * A manual payment submission: the user pays outside Paystack (mobile money,
 * bank transfer, etc.) and submits proof - a screenshot link plus the details
 * below - for an admin to manually verify and approve.
 *
 * Lifecycle:
 *  PENDING  -> submitted, awaiting admin review
 *  APPROVED -> admin confirmed the money came in; account gets its
 *              password/plan assigned exactly like a Paystack payment would
 *  REJECTED -> admin could not verify it; account is untouched, user may
 *              submit again
 */
public class ManualPayment {

    private String id;
    private String email;
    private Plan plan;

    // What the user submits as proof
    private String accountName;       // name on the sending account
    private String accountNumber;     // phone number / account number used to pay
    private String networkOrBank;     // e.g. "MTN Mobile Money", "Vodafone Cash", "GCB Bank"
    private String reference;         // transaction reference/ID the user was given
    private String screenshotUrl;     // link to the uploaded proof screenshot (e.g. hosted image URL)

    private ManualPaymentStatus status;
    private String rejectionReason;
    private boolean passwordRevealed; // true once the generated password has been shown to the user once

    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
    private String reviewedByAdmin;

    public ManualPayment(String id, String email, Plan plan, String accountName, String accountNumber,
                         String networkOrBank, String reference, String screenshotUrl) {
        this.id = id;
        this.email = email;
        this.plan = plan;
        this.accountName = accountName;
        this.accountNumber = accountNumber;
        this.networkOrBank = networkOrBank;
        this.reference = reference;
        this.screenshotUrl = screenshotUrl;
        this.status = ManualPaymentStatus.PENDING;
        this.passwordRevealed = false;
        this.submittedAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public Plan getPlan() {
        return plan;
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

    public ManualPaymentStatus getStatus() {
        return status;
    }

    public void setStatus(ManualPaymentStatus status) {
        this.status = status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public boolean isPasswordRevealed() {
        return passwordRevealed;
    }

    public void setPasswordRevealed(boolean passwordRevealed) {
        this.passwordRevealed = passwordRevealed;
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
}