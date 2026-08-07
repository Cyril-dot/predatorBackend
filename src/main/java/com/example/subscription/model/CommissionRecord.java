package com.example.subscription.model;

import java.time.LocalDateTime;

/**
 * Created once, at the moment a referred user's payment is verified.
 * Records the 70/30 (or whatever the admin's split is) breakdown of that
 * single payment.
 */
public class CommissionRecord {

    private String id;
    private String paymentReference;
    private String adminUsername;
    private String referredUserEmail;
    private Plan plan;
    private int amountCedis;        // total amount paid
    private double adminShareCedis; // admin's cut
    private double platformShareCedis; // platform's cut
    private LocalDateTime createdAt;
    private boolean paidOut;
    private LocalDateTime paidOutAt;
    private String payoutId;

    public CommissionRecord(String id, String paymentReference, String adminUsername, String referredUserEmail,
                             Plan plan, int amountCedis, double adminShareCedis, double platformShareCedis) {
        this.id = id;
        this.paymentReference = paymentReference;
        this.adminUsername = adminUsername;
        this.referredUserEmail = referredUserEmail;
        this.plan = plan;
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

    public Plan getPlan() {
        return plan;
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
