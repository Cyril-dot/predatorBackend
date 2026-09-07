package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.Admin;
import com.example.subscription.model.Plan;
import com.example.subscription.model.UserAccount;
import com.example.subscription.repository.InMemoryAdminRepository;
import com.example.subscription.repository.InMemoryUserRepository;
import com.example.subscription.util.CodeGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final InMemoryUserRepository userRepository;
    private final InMemoryAdminRepository adminRepository;

    @Value("${account.password.length:10}")
    private int passwordLength;

    public AccountService(InMemoryUserRepository userRepository, InMemoryAdminRepository adminRepository) {
        this.userRepository = userRepository;
        this.adminRepository = adminRepository;
    }

    /**
     * Step 1 of the flow: user registers with just their email, optionally
     * with an admin's referral code (e.g. from a referral link like
     * https://yourapp.com/register?ref=REF-8K3PQZ). No password is set here
     * - the account sits "unpaid" until they pay. Idempotent: registering
     * again with an email that has no active subscription just returns the
     * existing account as-is (its original referral attribution, if any, is
     * kept).
     */
    public synchronized UserAccount register(String email, String referralCode) {
        UserAccount account = userRepository.findByUsername(email).orElse(null);

        if (account == null) {
            account = new UserAccount(email);
            if (referralCode != null && !referralCode.isBlank()) {
                Admin admin = adminRepository.findByReferralCode(referralCode)
                        .orElseThrow(() -> new ApiException("Invalid referral code", HttpStatus.BAD_REQUEST));
                if (!admin.isActive()) {
                    throw new ApiException("This referral link is no longer active", HttpStatus.BAD_REQUEST);
                }
                account.setReferredByAdminCode(referralCode);
            }
            userRepository.save(account);
            return account;
        }

        if (account.hasActiveSubscription()) {
            throw new ApiException(
                    "This email already has an active subscription. Finish using it before registering/subscribing again.",
                    HttpStatus.CONFLICT);
        }

        // Already registered, no active subscription - nothing to do, just return it.
        return account;
    }

    /**
     * Called only from the payment-verify step, after Paystack confirms
     * success. Requires the email to already be registered, and requires
     * that it does NOT currently have an active/unused subscription
     * (one subscription at a time - no topping up, no stacking).
     */
    public synchronized UserAccount assignSubscription(String email, Plan plan) {
        UserAccount account = userRepository.findByUsername(email)
                .orElseThrow(() -> new ApiException(
                        "No registered account found for " + email + ". Please register first.",
                        HttpStatus.NOT_FOUND));

        if (account.hasActiveSubscription()) {
            throw new ApiException(
                    "This account already has an active subscription. You can only have one at a time.",
                    HttpStatus.CONFLICT);
        }

        String password = CodeGenerator.generatePassword(passwordLength);

        account.setPassword(password);
        account.setPlan(plan);
        account.setUsageExpiresAt(null);          // countdown starts on next login
        account.setSubscriptionConsumed(false);
        account.setActiveSessionToken(null);

        userRepository.save(account);
        return account;
    }

    /** Can this email currently pay for a plan? Must be registered and have no active subscription. */
    public boolean canPurchase(String email) {
        return userRepository.findByUsername(email)
                .map(a -> !a.hasActiveSubscription())
                .orElse(false); // not registered at all -> cannot purchase
    }

    /** Returns the account for this email, or null if not registered. Used for password lookups after approval. */
    public UserAccount getAccountOrNull(String email) {
        return userRepository.findByUsername(email).orElse(null);
    }
}
