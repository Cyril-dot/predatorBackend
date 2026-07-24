package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.model.ManualPayment;
import com.example.subscription.service.ManualPaymentService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-facing manual payment flow: for mobile money / bank transfer
 * payments made outside Paystack. The user pays directly to a number/account
 * you've shared with them, then submits proof here for an admin to review.
 *
 * The proof screenshot itself is uploaded elsewhere by the client (e.g. an
 * image host or storage bucket) and this endpoint just takes the resulting
 * link.
 *
 * Flow:
 *   1. POST /api/payment/manual/submit  -> user submits screenshot link + details, status = PENDING
 *   2. (admin reviews via /api/admin/manual-payments/**)
 *   3. GET  /api/payment/manual/status/{id} -> user polls this; once an admin
 *      approves it, this response includes the generated password ONCE.
 */
@RestController
@RequestMapping("/api/payment/manual")
public class ManualPaymentController {

    private final ManualPaymentService manualPaymentService;

    public ManualPaymentController(ManualPaymentService manualPaymentService) {
        this.manualPaymentService = manualPaymentService;
    }

    @PostMapping("/submit")
    public ApiResponse<Object> submit(
            @RequestParam String email,
            @RequestParam String plan,
            @RequestParam String accountName,
            @RequestParam String accountNumber,
            @RequestParam String networkOrBank,
            @RequestParam String reference,
            @RequestParam("screenshotUrl") String screenshotUrl) {

        ManualPayment payment = manualPaymentService.submit(
                email, plan, accountName, accountNumber, networkOrBank, reference, screenshotUrl);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", payment.getId());
        result.put("status", payment.getStatus());
        result.put("plan", payment.getPlan().name());
        result.put("submittedAt", payment.getSubmittedAt());
        result.put("message", "Submitted. Save this id - check its status with GET /api/payment/manual/status/{id}.");

        return ApiResponse.ok("Payment proof submitted, awaiting admin review", result);
    }

    /**
     * Poll this with the id returned from /submit. Once an admin approves
     * the payment, the response includes the generated login password -
     * shown exactly once, on the first status check after approval.
     */
    @GetMapping("/status/{id}")
    public ApiResponse<Object> status(@PathVariable String id) {
        ManualPaymentService.StatusResult result = manualPaymentService.checkStatus(id);
        ManualPayment payment = result.payment;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", payment.getId());
        data.put("status", payment.getStatus());
        data.put("plan", payment.getPlan().name());
        data.put("submittedAt", payment.getSubmittedAt());
        data.put("reviewedAt", payment.getReviewedAt());

        if (payment.getStatus().name().equals("REJECTED")) {
            data.put("rejectionReason", payment.getRejectionReason());
        }

        if (result.password != null) {
            data.put("username", payment.getEmail());
            data.put("password", result.password);
            data.put("message", "Save this password now - it will not be shown again. Use it to log in.");
        } else if (payment.getStatus().name().equals("APPROVED")) {
            data.put("message", "Already approved - use the password you saved earlier to log in.");
        }

        return ApiResponse.ok("Manual payment status", data);
    }
}