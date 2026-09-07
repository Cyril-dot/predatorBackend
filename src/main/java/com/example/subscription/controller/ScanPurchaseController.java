package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.model.ScanPlan;
import com.example.subscription.model.ScanPurchase;
import com.example.subscription.service.ScanPurchaseService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-facing flow for buying an AI betting-slip scan. Payment is manual
 * only (mobile money / bank transfer), same pattern as
 * {@code ManualPaymentController}:
 *
 *   1. POST /api/scan/payment/submit -> user submits screenshot link + plan, status = PENDING
 *   2. (admin reviews via /api/admin/scan-purchases/**)
 *   3. GET  /api/scan/payment/status/{id} -> user polls this; once APPROVED,
 *      they can call POST /api/scan/analyze with this purchase id + the slip image.
 *
 * Plans: SCAN_300 (2 picks), SCAN_500 (5 picks), SCAN_1000 (full coverage).
 */
@RestController
@RequestMapping("/api/scan/payment")
public class ScanPurchaseController {

    private final ScanPurchaseService scanPurchaseService;

    public ScanPurchaseController(ScanPurchaseService scanPurchaseService) {
        this.scanPurchaseService = scanPurchaseService;
    }

    @GetMapping("/plans")
    public ApiResponse<Object> plans() {
        var list = java.util.Arrays.stream(ScanPlan.values())
                .map(this::planSummary)
                .toList();
        return ApiResponse.ok("Available AI scan plans", list);
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

        ScanPurchase purchase = scanPurchaseService.submit(
                email, plan, accountName, accountNumber, networkOrBank, reference, screenshotUrl);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", purchase.getId());
        result.put("status", purchase.getStatus());
        result.put("scanPlan", purchase.getScanPlan().name());
        result.put("maxPicks", purchase.getScanPlan().isFullCoverage() ? "FULL" : purchase.getScanPlan().getMaxPicks());
        result.put("submittedAt", purchase.getSubmittedAt());
        result.put("message", "Submitted, awaiting admin review. Check status with GET /api/scan/payment/status/{id}.");

        return ApiResponse.ok("Scan payment proof submitted", result);
    }

    @GetMapping("/status/{id}")
    public ApiResponse<Object> status(@PathVariable String id) {
        ScanPurchase purchase = scanPurchaseService.getById(id);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", purchase.getId());
        data.put("status", purchase.getStatus());
        data.put("scanPlan", purchase.getScanPlan().name());
        data.put("maxPicks", purchase.getScanPlan().isFullCoverage() ? "FULL" : purchase.getScanPlan().getMaxPicks());
        data.put("submittedAt", purchase.getSubmittedAt());
        data.put("reviewedAt", purchase.getReviewedAt());

        if (purchase.getStatus().name().equals("REJECTED")) {
            data.put("rejectionReason", purchase.getRejectionReason());
        } else if (purchase.getStatus().name().equals("APPROVED")) {
            data.put("message", "Approved! Submit your slip image to POST /api/scan/analyze with this purchase id.");
        } else if (purchase.getStatus().name().equals("USED")) {
            data.put("usedAt", purchase.getUsedAt());
            data.put("message", "This purchase's scan has already been used.");
        }

        return ApiResponse.ok("Scan payment status", data);
    }

    private Map<String, Object> planSummary(ScanPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.getCode());
        m.put("name", p.name());
        m.put("amountCedis", p.getAmountCedis());
        m.put("maxPicks", p.isFullCoverage() ? "FULL" : p.getMaxPicks());
        return m;
    }
}
