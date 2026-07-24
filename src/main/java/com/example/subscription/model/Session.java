package com.example.subscription.model;

import java.time.LocalDateTime;

/**
 * A single active login session for a username. Only one Session per
 * username can be active at any time (enforced in SessionService), which is
 * what stops a user from sharing their credentials with someone else while
 * they are logged in.
 */
public class Session {

    private String token;
    private String username;
    private LocalDateTime loginAt;
    private LocalDateTime expiresAt;
    private String ipAddress;
    private boolean active;

    public Session(String token, String username, LocalDateTime loginAt, LocalDateTime expiresAt, String ipAddress) {
        this.token = token;
        this.username = username;
        this.loginAt = loginAt;
        this.expiresAt = expiresAt;
        this.ipAddress = ipAddress;
        this.active = true;
    }

    public String getToken() {
        return token;
    }

    public String getUsername() {
        return username;
    }

    public LocalDateTime getLoginAt() {
        return loginAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public String getIpAddress() {
        return ipAddress;
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

    public long secondsRemaining() {
        long secs = java.time.Duration.between(LocalDateTime.now(), expiresAt).getSeconds();
        return Math.max(secs, 0);
    }
}
