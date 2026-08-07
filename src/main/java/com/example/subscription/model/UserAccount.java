package com.example.subscription.model;

import java.time.LocalDateTime;

/**
 * Represents a registered account.
 * Username = the email the person registered with.
 *
 * Lifecycle:
 *  1. register()    -> account created, password = null, plan = null (UNPAID)
 *  2. pay + verify   -> password generated, plan attached (PAID / unused)
 *  3. login          -> usageExpiresAt set to (loginTime + plan.hours), countdown starts
 *  4. time elapses / logout after expiry -> subscriptionConsumed = true, account is
 *     back to "no active subscription" - only then can the same email pay for a
 *     brand new plan. One subscription at a time, no topping up.
 *
 * usageExpiresAt is null until the very first login. It is set the moment the
 * user logs in for the first time to (loginTime + plan.hours). This is the
 * hard wall-clock deadline after which the account can no longer be used,
 * regardless of how many times the user logs in/out in between.
 */
public class UserAccount {

    private String username; // email
    private String password; // null until a payment is verified; plaintext, in-memory only
    private Plan plan;       // null until a payment is verified
    private LocalDateTime createdAt;
    private LocalDateTime usageExpiresAt; // set on first login
    private String activeSessionToken;   // enforces single active session
    private boolean subscriptionConsumed; // true once usage window has fully elapsed
    private String referredByAdminCode;   // referral code used at registration, if any

    /** Used at registration time - no password/plan yet. */
    public UserAccount(String username) {
        this.username = username;
        this.password = null;
        this.plan = null;
        this.createdAt = LocalDateTime.now();
        this.subscriptionConsumed = false;
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

    public Plan getPlan() {
        return plan;
    }

    public void setPlan(Plan plan) {
        this.plan = plan;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUsageExpiresAt() {
        return usageExpiresAt;
    }

    public void setUsageExpiresAt(LocalDateTime usageExpiresAt) {
        this.usageExpiresAt = usageExpiresAt;
    }

    public String getActiveSessionToken() {
        return activeSessionToken;
    }

    public void setActiveSessionToken(String activeSessionToken) {
        this.activeSessionToken = activeSessionToken;
    }

    public boolean isSubscriptionConsumed() {
        return subscriptionConsumed;
    }

    public void setSubscriptionConsumed(boolean subscriptionConsumed) {
        this.subscriptionConsumed = subscriptionConsumed;
    }

    /** True once the plan's total window (from first login) has passed. */
    public boolean isWindowExpired() {
        return usageExpiresAt != null && LocalDateTime.now().isAfter(usageExpiresAt);
    }

    public boolean hasStartedUsage() {
        return usageExpiresAt != null;
    }

    public String getReferredByAdminCode() {
        return referredByAdminCode;
    }

    public void setReferredByAdminCode(String referredByAdminCode) {
        this.referredByAdminCode = referredByAdminCode;
    }

    /** True once a payment has been verified and a password/plan attached. */
    public boolean isPaid() {
        return password != null && plan != null;
    }

    /**
     * True if this account currently "owns" a subscription that hasn't been
     * fully used up yet - i.e. paid, but not yet consumed/expired. While this
     * is true, the same email is NOT allowed to pay for another plan
     * (one subscription at a time, no topping up).
     */
    public boolean hasActiveSubscription() {
        return isPaid() && !subscriptionConsumed && !isWindowExpired();
    }
}
