package com.example.subscription.model;

import java.time.LocalDateTime;

public class PaymentTransaction {

    public enum Status { PENDING, SUCCESS, FAILED }

    private String reference;
    private String email;
    private Plan plan;
    private Status status;
    private LocalDateTime createdAt;
    private LocalDateTime verifiedAt;

    public PaymentTransaction(String reference, String email, Plan plan) {
        this.reference = reference;
        this.email = email;
        this.plan = plan;
        this.status = Status.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public String getReference() {
        return reference;
    }

    public String getEmail() {
        return email;
    }

    public Plan getPlan() {
        return plan;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }
}
