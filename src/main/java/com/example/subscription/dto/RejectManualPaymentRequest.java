package com.example.subscription.dto;

public class RejectManualPaymentRequest {

    /** Optional - shown back to the user so they know why it was rejected. */
    private String reason;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
