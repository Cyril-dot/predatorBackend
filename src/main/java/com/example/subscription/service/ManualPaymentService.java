package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.ManualPayment;
import com.example.subscription.model.ManualPaymentStatus;
import com.example.subscription.model.Plan;
import com.example.subscription.model.UserAccount;
import com.example.subscription.repository.InMemoryManualPaymentRepository;
import com.example.subscription.util.CodeGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Handles manual payment submissions (mobile money / bank transfer proof).
 * The payment screenshot is not uploaded to this service directly - the
 * client uploads it elsewhere (e.g. an image host / storage bucket) and
 * submits the resulting link, which is stored as plain text on the
 * ManualPayment record - consistent with the rest of this app's
 * "everything in memory, nothing persists across a restart" design. No
 * files are written to or read from disk here.
 */
@Service
public class ManualPaymentService {

    private final InMemoryManualPaymentRepository manualPaymentRepository;
    private final AccountService accountService;
    private final CommissionService commissionService;

    public ManualPaymentService(InMemoryManualPaymentRepository manualPaymentRepository,
                                AccountService accountService,
                                CommissionService commissionService) {
        this.manualPaymentRepository = manualPaymentRepository;
        this.accountService = accountService;
        this.commissionService = commissionService;
    }

    /**
     * User submits proof of a manual payment (mobile money / bank transfer)
     * made outside Paystack. Requires the email to already be registered and
     * to have no currently active/unused subscription, same as the Paystack
     * flow - one subscription at a time.
     */
    public ManualPayment submit(String email, String planCode, String accountName, String accountNumber,
                                String networkOrBank, String reference, String screenshotUrl) {

        if (!accountService.canPurchase(email)) {
            throw new ApiException(
                    "Cannot submit payment: either " + email + " is not registered yet, " +
                            "or it already has an active subscription (one at a time).",
                    HttpStatus.CONFLICT);
        }

        Plan plan = Plan.fromCode(planCode);

        if (screenshotUrl == null || screenshotUrl.isBlank()) {
            throw new ApiException("A payment screenshot link is required", HttpStatus.BAD_REQUEST);
        }
        if (accountName == null || accountName.isBlank()) {
            throw new ApiException("accountName is required", HttpStatus.BAD_REQUEST);
        }
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new ApiException("accountNumber is required", HttpStatus.BAD_REQUEST);
        }
        if (networkOrBank == null || networkOrBank.isBlank()) {
            throw new ApiException("networkOrBank is required", HttpStatus.BAD_REQUEST);
        }
        if (reference == null || reference.isBlank()) {
            throw new ApiException("reference is required", HttpStatus.BAD_REQUEST);
        }

        String trimmedUrl = screenshotUrl.trim();
        if (!isValidHttpUrl(trimmedUrl)) {
            throw new ApiException(
                    "screenshotUrl must be a valid http(s) URL", HttpStatus.BAD_REQUEST);
        }

        String id = CodeGenerator.generateId();

        ManualPayment payment = new ManualPayment(
                id, email, plan, accountName.trim(), accountNumber.trim(),
                networkOrBank.trim(), reference.trim(), trimmedUrl);

        manualPaymentRepository.save(payment);
        return payment;
    }

    private boolean isValidHttpUrl(String value) {
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    public ManualPayment getById(String id) {
        return manualPaymentRepository.findById(id)
                .orElseThrow(() -> new ApiException("Manual payment submission not found", HttpStatus.NOT_FOUND));
    }

    public List<ManualPayment> listAll() {
        return manualPaymentRepository.findAll();
    }

    public List<ManualPayment> listPending() {
        return manualPaymentRepository.findByStatus(ManualPaymentStatus.PENDING);
    }

    public List<ManualPayment> listForEmail(String email) {
        return manualPaymentRepository.findByEmail(email);
    }

    /**
     * User-facing status check. If the submission was just approved and the
     * password hasn't been shown yet, this is where it gets revealed -
     * exactly once, mirroring how /api/payment/verify works for Paystack.
     */
    public synchronized StatusResult checkStatus(String id) {
        ManualPayment payment = getById(id);

        String password = null;
        if (payment.getStatus() == ManualPaymentStatus.APPROVED && !payment.isPasswordRevealed()) {
            UserAccount account = accountService.getAccountOrNull(payment.getEmail());
            if (account != null && account.getPassword() != null) {
                password = account.getPassword();
                payment.setPasswordRevealed(true);
                manualPaymentRepository.save(payment);
            }
        }

        return new StatusResult(payment, password);
    }

    /**
     * Admin approves a pending manual payment: attaches the plan/password to
     * the account exactly like a successful Paystack verification would, and
     * records referral commission if applicable.
     */
    public synchronized ManualPayment approve(String id, String adminUsername) {
        ManualPayment payment = getById(id);

        if (payment.getStatus() != ManualPaymentStatus.PENDING) {
            throw new ApiException("This submission has already been " + payment.getStatus(), HttpStatus.CONFLICT);
        }

        UserAccount account = accountService.assignSubscription(payment.getEmail(), payment.getPlan());

        commissionService.recordIfReferred(
                "MANUAL_" + payment.getId(), payment.getEmail(), payment.getPlan(),
                payment.getPlan().getAmountCedis(), account.getReferredByAdminCode());

        payment.setStatus(ManualPaymentStatus.APPROVED);
        payment.setReviewedAt(LocalDateTime.now());
        payment.setReviewedByAdmin(adminUsername);
        manualPaymentRepository.save(payment);

        return payment;
    }

    public synchronized ManualPayment reject(String id, String adminUsername, String reason) {
        ManualPayment payment = getById(id);

        if (payment.getStatus() != ManualPaymentStatus.PENDING) {
            throw new ApiException("This submission has already been " + payment.getStatus(), HttpStatus.CONFLICT);
        }

        payment.setStatus(ManualPaymentStatus.REJECTED);
        payment.setRejectionReason(reason != null && !reason.isBlank() ? reason : "Not specified");
        payment.setReviewedAt(LocalDateTime.now());
        payment.setReviewedByAdmin(adminUsername);
        manualPaymentRepository.save(payment);

        return payment;
    }

    /** Small holder so the controller can access both the record and the (maybe-null) revealed password. */
    public static class StatusResult {
        public final ManualPayment payment;
        public final String password; // non-null only on the one call where it's first revealed

        public StatusResult(ManualPayment payment, String password) {
            this.payment = payment;
            this.password = password;
        }
    }
}