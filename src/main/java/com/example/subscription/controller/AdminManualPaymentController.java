package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.dto.RejectManualPaymentRequest;
import com.example.subscription.model.AdminSession;
import com.example.subscription.model.ManualPayment;
import com.example.subscription.service.ManualPaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Guarded by AdminAuthFilter (any request under /api/admin/** requires a
 * valid ADMIN or SUPER_ADMIN session). This is where admins review manual
 * payment submissions (mobile money / bank transfer proof) and approve or
 * reject them.
 */
@RestController
@RequestMapping("/api/admin/manual-payments")
public class AdminManualPaymentController {

    private final ManualPaymentService manualPaymentService;

    public AdminManualPaymentController(ManualPaymentService manualPaymentService) {
        this.manualPaymentService = manualPaymentService;
    }

    private String currentUsername(HttpServletRequest request) {
        return ((AdminSession) request.getAttribute("adminSession")).getUsername();
    }

    @GetMapping
    public ApiResponse<Object> listAll() {
        List<Map<String, Object>> list = manualPaymentService.listAll().stream()
                .map(this::summary)
                .collect(Collectors.toList());
        return ApiResponse.ok("All manual payment submissions (" + list.size() + ")", list);
    }

    @GetMapping("/pending")
    public ApiResponse<Object> listPending() {
        List<Map<String, Object>> list = manualPaymentService.listPending().stream()
                .map(this::summary)
                .collect(Collectors.toList());
        return ApiResponse.ok("Pending manual payment submissions (" + list.size() + ")", list);
    }

    @GetMapping("/{id}")
    public ApiResponse<Object> getOne(@PathVariable String id) {
        return ApiResponse.ok("Manual payment details", summary(manualPaymentService.getById(id)));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Object> approve(@PathVariable String id, HttpServletRequest request) {
        ManualPayment payment = manualPaymentService.approve(id, currentUsername(request));
        return ApiResponse.ok(
                "Approved. The user will see their password next time they check status.",
                summary(payment));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Object> reject(@PathVariable String id,
                                      @RequestBody(required = false) RejectManualPaymentRequest req,
                                      HttpServletRequest request) {
        String reason = req != null ? req.getReason() : null;
        ManualPayment payment = manualPaymentService.reject(id, currentUsername(request), reason);
        return ApiResponse.ok("Rejected", summary(payment));
    }

    private Map<String, Object> summary(ManualPayment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("email", p.getEmail());
        m.put("plan", p.getPlan().name());
        m.put("amountCedis", p.getPlan().getAmountCedis());
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
        return m;
    }
}