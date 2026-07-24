package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.model.Admin;
import com.example.subscription.model.AdminSession;
import com.example.subscription.model.CommissionRecord;
import com.example.subscription.model.PayoutRecord;
import com.example.subscription.model.UserAccount;
import com.example.subscription.repository.InMemoryUserRepository;
import com.example.subscription.service.AdminService;
import com.example.subscription.service.CommissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Everything here is guarded by AdminAuthFilter - requires a valid admin
 * session (ADMIN or SUPER_ADMIN role). A super admin can call these too,
 * to see their own referral performance if they refer users themselves.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final CommissionService commissionService;
    private final InMemoryUserRepository userRepository;

    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    public AdminController(AdminService adminService, CommissionService commissionService,
                            InMemoryUserRepository userRepository) {
        this.adminService = adminService;
        this.commissionService = commissionService;
        this.userRepository = userRepository;
    }

    private AdminSession currentSession(HttpServletRequest request) {
        return (AdminSession) request.getAttribute("adminSession");
    }

    @GetMapping("/me")
    public ApiResponse<Object> me(HttpServletRequest request) {
        Admin admin = adminService.getByUsername(currentSession(request).getUsername());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", admin.getUsername());
        result.put("role", admin.getRole());
        result.put("referralCode", admin.getReferralCode());
        result.put("referralLink", frontendBaseUrl + "/register?ref=" + admin.getReferralCode());
        result.put("adminSharePercent", admin.getAdminSharePercent());
        result.put("platformSharePercent", admin.getPlatformSharePercent());
        result.put("active", admin.isActive());
        result.put("createdAt", admin.getCreatedAt());

        return ApiResponse.ok("Admin profile", result);
    }

    /** The shareable referral link + raw code, front and center. */
    @GetMapping("/referral")
    public ApiResponse<Object> referral(HttpServletRequest request) {
        Admin admin = adminService.getByUsername(currentSession(request).getUsername());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("referralCode", admin.getReferralCode());
        result.put("referralLink", frontendBaseUrl + "/register?ref=" + admin.getReferralCode());
        result.put("split", admin.getAdminSharePercent() + "/" + admin.getPlatformSharePercent()
                + " (admin/platform)");

        return ApiResponse.ok("Referral link", result);
    }

    /** General oversight: every registered user on the platform. */
    @GetMapping("/users")
    public ApiResponse<Object> users() {
        List<Map<String, Object>> list = userRepository.findAll().stream()
                .map(this::userSummary)
                .collect(Collectors.toList());
        return ApiResponse.ok("All users (" + list.size() + ")", list);
    }

    /** Just the users this admin personally referred. */
    @GetMapping("/users/referred")
    public ApiResponse<Object> referredUsers(HttpServletRequest request) {
        Admin admin = adminService.getByUsername(currentSession(request).getUsername());

        List<Map<String, Object>> list = userRepository.findAll().stream()
                .filter(u -> admin.getReferralCode().equals(u.getReferredByAdminCode()))
                .map(this::userSummary)
                .collect(Collectors.toList());

        return ApiResponse.ok("Users referred by you (" + list.size() + ")", list);
    }

    private Map<String, Object> userSummary(UserAccount u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        m.put("paid", u.isPaid());
        m.put("plan", u.getPlan() != null ? u.getPlan().name() : null);
        m.put("hasActiveSession", u.getActiveSessionToken() != null);
        m.put("subscriptionConsumed", u.isSubscriptionConsumed());
        m.put("referredByAdminCode", u.getReferredByAdminCode());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }

    /** This admin's own referral performance: revenue generated + commission earned. */
    @GetMapping("/stats")
    public ApiResponse<Object> stats(HttpServletRequest request) {
        String username = currentSession(request).getUsername();
        Admin admin = adminService.getByUsername(username);

        LocalDate today = LocalDate.now();
        LocalDateTime weekStart = LocalDateTime.now().minusDays(7);

        long referredUserCount = userRepository.findAll().stream()
                .filter(u -> admin.getReferralCode().equals(u.getReferredByAdminCode()))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("referralCode", admin.getReferralCode());
        result.put("split", admin.getAdminSharePercent() + "/" + admin.getPlatformSharePercent());
        result.put("referredUserCount", referredUserCount);
        result.put("commissionEarnedToday", commissionService.earnedOn(username, today));
        result.put("commissionEarnedThisWeek", commissionService.earnedSince(username, weekStart));
        result.put("totalCommissionEarned", commissionService.totalEarned(username));
        result.put("totalCommissionPending", commissionService.totalPending(username));
        result.put("totalCommissionPaidOut", commissionService.totalPaidOut(username));

        return ApiResponse.ok("Your referral stats", result);
    }

    /** Every commission this admin has earned, itemized per referred payment. */
    @GetMapping("/commissions")
    public ApiResponse<Object> commissions(HttpServletRequest request) {
        String username = currentSession(request).getUsername();
        List<CommissionRecord> records = commissionService.listForAdmin(username);
        return ApiResponse.ok("Commission history (" + records.size() + " records)", records);
    }

    /** Payout receipts this admin has received from a super admin. */
    @GetMapping("/payouts")
    public ApiResponse<Object> payouts(HttpServletRequest request) {
        String username = currentSession(request).getUsername();
        List<PayoutRecord> records = commissionService.payoutsForAdmin(username);
        return ApiResponse.ok("Payout history (" + records.size() + " payouts)", records);
    }
}
