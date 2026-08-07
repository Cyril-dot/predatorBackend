package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.ScanPlan;
import com.example.subscription.model.ScanPurchase;
import com.example.subscription.model.ScanPurchaseStatus;
import com.example.subscription.repository.InMemoryScanPurchaseRepository;
import com.example.subscription.util.CodeGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Handles manual payment submissions for AI betting-slip scans (see
 * {@link ScanPlan}). Payment is manual only, same as ManualPayment: the user
 * pays mobile money/bank transfer outside the app and submits proof here for
 * an admin to review. Approving a submission does NOT run the AI scan - it
 * just unlocks a single call to POST /api/scan/analyze for that purchase id.
 */
@Service
public class ScanPurchaseService {

    private final InMemoryScanPurchaseRepository scanPurchaseRepository;

    public ScanPurchaseService(InMemoryScanPurchaseRepository scanPurchaseRepository) {
        this.scanPurchaseRepository = scanPurchaseRepository;
    }

    /**
     * A given email can only have one scan purchase "in flight" at a time -
     * i.e. no other submission that is still PENDING or APPROVED-but-not-yet-
     * used. Once a purchase is USED or REJECTED, they're free to submit
     * another.
     */
    public boolean canSubmit(String email) {
        return scanPurchaseRepository.findByEmail(email).stream()
                .noneMatch(p -> p.getStatus() == ScanPurchaseStatus.PENDING
                        || p.getStatus() == ScanPurchaseStatus.APPROVED);
    }

    public ScanPurchase submit(String email, String scanPlanCode, String accountName, String accountNumber,
                               String networkOrBank, String reference, String screenshotUrl) {

        if (email == null || email.isBlank()) {
            throw new ApiException("email is required", HttpStatus.BAD_REQUEST);
        }
        if (!canSubmit(email)) {
            throw new ApiException(
                    "You already have a scan purchase that's pending review or approved and not yet used. " +
                            "Finish using it before submitting another.",
                    HttpStatus.CONFLICT);
        }

        ScanPlan scanPlan = ScanPlan.fromCode(scanPlanCode);

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
            throw new ApiException("screenshotUrl must be a valid http(s) URL", HttpStatus.BAD_REQUEST);
        }

        String id = CodeGenerator.generateId();

        ScanPurchase purchase = new ScanPurchase(
                id, email.trim(), scanPlan, accountName.trim(), accountNumber.trim(),
                networkOrBank.trim(), reference.trim(), trimmedUrl);

        scanPurchaseRepository.save(purchase);
        return purchase;
    }

    private boolean isValidHttpUrl(String value) {
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    public ScanPurchase getById(String id) {
        return scanPurchaseRepository.findById(id)
                .orElseThrow(() -> new ApiException("Scan purchase not found", HttpStatus.NOT_FOUND));
    }

    public List<ScanPurchase> listAll() {
        return scanPurchaseRepository.findAll();
    }

    public List<ScanPurchase> listPending() {
        return scanPurchaseRepository.findByStatus(ScanPurchaseStatus.PENDING);
    }

    public List<ScanPurchase> listForEmail(String email) {
        return scanPurchaseRepository.findByEmail(email);
    }

    public synchronized ScanPurchase approve(String id, String adminUsername) {
        ScanPurchase purchase = getById(id);

        if (purchase.getStatus() != ScanPurchaseStatus.PENDING) {
            throw new ApiException("This submission has already been " + purchase.getStatus(), HttpStatus.CONFLICT);
        }

        purchase.setStatus(ScanPurchaseStatus.APPROVED);
        purchase.setReviewedAt(LocalDateTime.now());
        purchase.setReviewedByAdmin(adminUsername);
        scanPurchaseRepository.save(purchase);

        return purchase;
    }

    public synchronized ScanPurchase reject(String id, String adminUsername, String reason) {
        ScanPurchase purchase = getById(id);

        if (purchase.getStatus() != ScanPurchaseStatus.PENDING) {
            throw new ApiException("This submission has already been " + purchase.getStatus(), HttpStatus.CONFLICT);
        }

        purchase.setStatus(ScanPurchaseStatus.REJECTED);
        purchase.setRejectionReason(reason != null && !reason.isBlank() ? reason : "Not specified");
        purchase.setReviewedAt(LocalDateTime.now());
        purchase.setReviewedByAdmin(adminUsername);
        scanPurchaseRepository.save(purchase);

        return purchase;
    }

    /** Marks a purchase as consumed once /api/scan/analyze has run for it. Guards against double-use. */
    public synchronized ScanPurchase markUsed(String id) {
        ScanPurchase purchase = getById(id);

        if (purchase.getStatus() != ScanPurchaseStatus.APPROVED) {
            throw new ApiException(
                    "This scan purchase is not ready to use (status: " + purchase.getStatus() + ")",
                    HttpStatus.CONFLICT);
        }

        purchase.setStatus(ScanPurchaseStatus.USED);
        purchase.setUsedAt(LocalDateTime.now());
        scanPurchaseRepository.save(purchase);

        return purchase;
    }
}
