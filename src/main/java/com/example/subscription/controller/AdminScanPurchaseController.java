package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.dto.RejectScanPurchaseRequest;
import com.example.subscription.model.AdminSession;
import com.example.subscription.model.ScanPurchase;
import com.example.subscription.service.ScanPurchaseService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Guarded by AdminAuthFilter (any request under /api/admin/** requires a
 * valid ADMIN or SUPER_ADMIN session). Admins review AI scan-purchase
 * submissions here - approving one just unlocks the user's single
 * POST /api/scan/analyze call, it does not run the AI itself.
 */
@RestController
@RequestMapping("/api/admin/scan-purchases")
public class AdminScanPurchaseController {

    private final ScanPurchaseService scanPurchaseService;

    public AdminScanPurchaseController(ScanPurchaseService scanPurchaseService) {
        this.scanPurchaseService = scanPurchaseService;
    }

    private String currentUsername(HttpServletRequest request) {
        return ((AdminSession) request.getAttribute("adminSession")).getUsername();
    }

    @GetMapping
    public ApiResponse<Object> listAll() {
        List<Map<String, Object>> list = scanPurchaseService.listAll().stream()
                .map(this::summary)
                .collect(Collectors.toList());
        return ApiResponse.ok("All scan purchase submissions (" + list.size() + ")", list);
    }

    @GetMapping("/pending")
    public ApiResponse<Object> listPending() {
        List<Map<String, Object>> list = scanPurchaseService.listPending().stream()
                .map(this::summary)
                .collect(Collectors.toList());
        return ApiResponse.ok("Pending scan purchase submissions (" + list.size() + ")", list);
    }

    @GetMapping("/{id}")
    public ApiResponse<Object> getOne(@PathVariable String id) {
        return ApiResponse.ok("Scan purchase details", summary(scanPurchaseService.getById(id)));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Object> approve(@PathVariable String id, HttpServletRequest request) {
        ScanPurchase purchase = scanPurchaseService.approve(id, currentUsername(request));
        return ApiResponse.ok(
                "Approved. The user can now submit their slip image to POST /api/scan/analyze.",
                summary(purchase));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Object> reject(@PathVariable String id,
                                      @RequestBody(required = false) RejectScanPurchaseRequest req,
                                      HttpServletRequest request) {
        String reason = req != null ? req.getReason() : null;
        ScanPurchase purchase = scanPurchaseService.reject(id, currentUsername(request), reason);
        return ApiResponse.ok("Rejected", summary(purchase));
    }

    private Map<String, Object> summary(ScanPurchase p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("email", p.getEmail());
        m.put("scanPlan", p.getScanPlan().name());
        m.put("amountCedis", p.getScanPlan().getAmountCedis());
        m.put("maxPicks", p.getScanPlan().isFullCoverage() ? "FULL" : p.getScanPlan().getMaxPicks());
        m.put("accountName", p.getAccountName());
        m.put("accountNumber", p.getAccountNumber());
        m.put("networkOrBank", p.getNetworkOrBank());
        m.put("reference", p.getReference());
        m.put("screenshotUrl", p.getScreenshotUrl());
        m.put("status", p.getStatus());
        m.put("rejectionReason", p.getRejectionReason());
        m.put("submittedAt", p.getSubmittedAt());
        m.put("reviewedAt", p.getReviewedAt());
        m.put("reviewedByAdmin", p.getReviewedByAdmin());
        m.put("usedAt", p.getUsedAt());
        return m;
    }
}
