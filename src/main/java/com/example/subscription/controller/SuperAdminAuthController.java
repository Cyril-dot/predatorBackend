package com.example.subscription.controller;

import com.example.subscription.dto.AdminLoginRequest;
import com.example.subscription.dto.ApiResponse;
import com.example.subscription.exception.ApiException;
import com.example.subscription.model.AdminRole;
import com.example.subscription.model.AdminSession;
import com.example.subscription.service.AdminAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dedicated login door for SUPER_ADMIN accounts only.
 *
 * A regular ADMIN account will get a 403 here even with a correct
 * password - they must use /api/admin/auth/login instead. This exists
 * separately from AdminAuthController so the super-admin entry point is
 * unmistakably its own thing, not just "the same login with a different
 * role in the response."
 */
@RestController
@RequestMapping("/api/superadmin/auth")
public class SuperAdminAuthController {

    private final AdminAuthService adminAuthService;

    public SuperAdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    /** Open endpoint - this is how a super admin gets a session token. */
    @PostMapping("/login")
    public ApiResponse<Object> login(@Valid @RequestBody AdminLoginRequest req) {
        AdminSession session = adminAuthService.login(req.getUsername(), req.getPassword(), AdminRole.SUPER_ADMIN);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", session.getToken());
        result.put("role", session.getRole());
        result.put("expiresAt", session.getExpiresAt());

        return ApiResponse.ok("Super admin login successful", result);
    }

    @PostMapping("/logout")
    public ApiResponse<Object> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ApiException("Missing Authorization Bearer token", HttpStatus.UNAUTHORIZED);
        }
        adminAuthService.logout(authHeader.substring(7));
        return ApiResponse.ok("Logged out successfully", null);
    }
}
