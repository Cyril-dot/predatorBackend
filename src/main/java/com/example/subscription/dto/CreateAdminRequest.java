package com.example.subscription.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class CreateAdminRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;

    /** "ADMIN" or "SUPER_ADMIN". Defaults to ADMIN if omitted. */
    private String role;

    /** Optional override of the default 30% admin / 70% platform split. */
    private Integer adminSharePercent;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getAdminSharePercent() {
        return adminSharePercent;
    }

    public void setAdminSharePercent(Integer adminSharePercent) {
        this.adminSharePercent = adminSharePercent;
    }
}
