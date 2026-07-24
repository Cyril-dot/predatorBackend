package com.example.subscription.model;

import java.time.LocalDateTime;

/**
 * Login session for an admin/super-admin. Unlike user Sessions, these are
 * not tied to a purchased time window - they just expire after a fixed
 * duration (admin.session.duration-hours) like a normal admin-panel login.
 */
public class AdminSession {

    private String token;
    private String username;
    private AdminRole role;
    private LocalDateTime loginAt;
    private LocalDateTime expiresAt;
    private boolean active;

    public AdminSession(String token, String username, AdminRole role, LocalDateTime loginAt, LocalDateTime expiresAt) {
        this.token = token;
        this.username = username;
        this.role = role;
        this.loginAt = loginAt;
        this.expiresAt = expiresAt;
        this.active = true;
    }

    public String getToken() {
        return token;
    }

    public String getUsername() {
        return username;
    }

    public AdminRole getRole() {
        return role;
    }

    public LocalDateTime getLoginAt() {
        return loginAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
