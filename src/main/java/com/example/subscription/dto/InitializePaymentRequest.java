package com.example.subscription.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class InitializePaymentRequest {

    @NotBlank
    @Email
    private String email;

    /** One of: TWO_HOUR, THREE_HOUR, FIVE_HOUR (or "2HR", "3HR", "5HR") */
    @NotNull
    private String plan;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }
}
