package com.example.subscription.model;

import java.time.LocalDateTime;

/**
 * An admin (or super admin) account. Admins own a unique referral code.
 * When a user registers using that code and later pays, the commission
 * from that payment is split between the admin and the platform according
 * to adminSharePercent / platformSharePercent (defaults to 30/70).
 */
public class Admin {

    private String username; // email
    private String password; // plaintext, in-memory only (hash before production)
    private AdminRole role;
    private String referralCode;
    private int adminSharePercent;    // e.g. 30
    private int platformSharePercent; // e.g. 70
    private boolean active;
    private LocalDateTime createdAt;

    public Admin(String username, String password, AdminRole role, String referralCode,
                 int adminSharePercent, int platformSharePercent) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.referralCode = referralCode;
        this.adminSharePercent = adminSharePercent;
        this.platformSharePercent = platformSharePercent;
        this.active = true;
        this.createdAt = LocalDateTime.now();
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public AdminRole getRole() {
        return role;
    }

    public void setRole(AdminRole role) {
        this.role = role;
    }

    public String getReferralCode() {
        return referralCode;
    }

    public int getAdminSharePercent() {
        return adminSharePercent;
    }

    public void setAdminSharePercent(int adminSharePercent) {
        this.adminSharePercent = adminSharePercent;
    }

    public int getPlatformSharePercent() {
        return platformSharePercent;
    }

    public void setPlatformSharePercent(int platformSharePercent) {
        this.platformSharePercent = platformSharePercent;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
