package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.dto.CreateAdminRequest;
import com.example.subscription.exception.ApiException;
import com.example.subscription.model.Admin;
import com.example.subscription.model.AdminRole;
import com.example.subscription.model.AdminSession;
import com.example.subscription.model.CommissionRecord;
import com.example.subscription.model.PayoutRecord;
import com.example.subscription.service.AdminService;
import com.example.subscription.service.CommissionService;
import com.example.subscription.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Everything here is guarded by AdminAuthFilter and requires the SUPER_ADMIN
 * role specifically (enforced in the filter for any /api/superadmin/** path).
 */
@RestController
@RequestMapping("/api/superadmin")
public class SuperAdminController {

    private final AdminService adminService;
    private final CommissionService commissionService;
    private final StatsService statsService;

    public SuperAdminController(AdminService adminService, CommissionService commissionService, StatsService statsService) {
        this.adminService = adminService;
        this.commissionService = commissionService;
        this.statsService = statsService;
    }

    private String currentUsername(HttpServletRequest request) {
        return ((AdminSession) request.getAttribute("adminSession")).getUsername();
    }

    // ---------------------------------------------------------------
    // Admin management
    // ---------------------------------------------------------------

    @PostMapping("/admins")
    public ApiResponse<Object> createAdmin(@Valid @RequestBody CreateAdminRequest req) {
        AdminRole role = parseRole(req.getRole());
        Admin admin = adminService.createAdmin(req.getEmail(), req.getPassword(), role, req.getAdminSharePercent());
        return ApiResponse.ok("Admin created", adminSummary(admin));
    }

    @GetMapping("/admins")
    public ApiResponse<Object> listAdmins() {
        List<Map<String, Object>> list = adminService.listAll().stream()
                .map(this::adminSummaryWithStats)
                .collect(Collectors.toList());
        return ApiResponse.ok("All admins (" + list.size() + ")", list);
    }

    @GetMapping("/admins/{username}")
    public ApiResponse<Object> getAdmin(@PathVariable String username) {
        Admin admin = adminService.getByUsername(username);
        return ApiResponse.ok("Admin details", adminSummaryWithStats(admin));
    }

    @PutMapping("/admins/{username}/deactivate")
    public ApiResponse<Object> deactivate(@PathVariable String username) {
        Admin admin = adminService.setActive(username, false);
        return ApiResponse.ok("Admin deactivated", adminSummary(admin));
    }

    @PutMapping("/admins/{username}/activate")
    public ApiResponse<Object> activate(@PathVariable String username) {
        Admin admin = adminService.setActive(username, true);
        return ApiResponse.ok("Admin activated", adminSummary(admin));
    }

    private Map<String, Object> adminSummary(Admin admin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", admin.getUsername());
        m.put("role", admin.getRole());
        m.put("referralCode", admin.getReferralCode());
        m.put("adminSharePercent", admin.getAdminSharePercent());
        m.put("platformSharePercent", admin.getPlatformSharePercent());
        m.put("active", admin.isActive());
        m.put("createdAt", admin.getCreatedAt());
        return m;
    }

    private Map<String, Object> adminSummaryWithStats(Admin admin) {
        Map<String, Object> m = adminSummary(admin);
        m.put("totalCommissionEarned", commissionService.totalEarned(admin.getUsername()));
        m.put("totalCommissionPending", commissionService.totalPending(admin.getUsername()));
        m.put("totalCommissionPaidOut", commissionService.totalPaidOut(admin.getUsername()));
        return m;
    }

    private AdminRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return AdminRole.ADMIN;
        }
        try {
            return AdminRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException("role must be ADMIN or SUPER_ADMIN", HttpStatus.BAD_REQUEST);
        }
    }

    // ---------------------------------------------------------------
    // Platform-wide stats
    // ---------------------------------------------------------------

    @GetMapping("/stats")
    public ApiResponse<Object> platformStats() {
        LocalDate today = LocalDate.now();
        LocalDateTime weekStart = LocalDateTime.now().minusDays(7);

        double totalCommissionsOwed = commissionService.listAll().stream()
                .filter(c -> !c.isPaidOut())
                .mapToDouble(CommissionRecord::getAdminShareCedis)
                .sum();

        double totalCommissionsPaid = commissionService.listAll().stream()
                .filter(CommissionRecord::isPaidOut)
                .mapToDouble(CommissionRecord::getAdminShareCedis)
                .sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("depositsToday", statsService.depositsOn(today));
        result.put("paymentsToday", statsService.successfulPaymentCountOn(today));
        result.put("depositsThisWeek", statsService.depositsSince(weekStart));
        result.put("depositsAllTime", statsService.totalDepositsAllTime());
        result.put("successfulPaymentsAllTime", statsService.successfulPaymentCount());
        result.put("totalCommissionsOwed", totalCommissionsOwed);
        result.put("totalCommissionsPaid", totalCommissionsPaid);
        result.put("totalAdmins", adminService.listAll().size());

        return ApiResponse.ok("Platform-wide stats", result);
    }

    @GetMapping("/commissions")
    public ApiResponse<Object> allCommissions() {
        List<CommissionRecord> records = commissionService.listAll();
        return ApiResponse.ok("All commission records (" + records.size() + ")", records);
    }

    // ---------------------------------------------------------------
    // Payouts
    // ---------------------------------------------------------------

    /** Pays out ALL of an admin's currently pending commission in one batch. */
    @PostMapping("/payouts/{adminUsername}")
    public ApiResponse<Object> payoutAdmin(@PathVariable String adminUsername, HttpServletRequest request) {
        PayoutRecord record = commissionService.payoutAdmin(adminUsername, currentUsername(request));
        return ApiResponse.ok("Payout recorded", record);
    }

    @GetMapping("/payouts")
    public ApiResponse<Object> allPayouts() {
        List<PayoutRecord> records = commissionService.allPayouts();
        return ApiResponse.ok("All payout records (" + records.size() + ")", records);
    }
}
