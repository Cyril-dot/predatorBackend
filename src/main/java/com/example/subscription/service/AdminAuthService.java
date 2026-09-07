package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.Admin;
import com.example.subscription.model.AdminRole;
import com.example.subscription.model.AdminSession;
import com.example.subscription.repository.InMemoryAdminSessionRepository;
import com.example.subscription.util.CodeGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AdminAuthService {

    private final AdminService adminService;
    private final InMemoryAdminSessionRepository adminSessionRepository;

    @Value("${admin.session.duration-hours:12}")
    private int sessionDurationHours;

    public AdminAuthService(AdminService adminService, InMemoryAdminSessionRepository adminSessionRepository) {
        this.adminService = adminService;
        this.adminSessionRepository = adminSessionRepository;
    }

    /** Shared login - accepts any active admin, regardless of role (ADMIN or SUPER_ADMIN). */
    public AdminSession login(String username, String password) {
        return login(username, password, null);
    }

    /**
     * Login restricted to a specific role. Used by SuperAdminAuthController
     * to reject regular ADMIN accounts even if they know a valid password -
     * the super admin login door only opens for SUPER_ADMIN accounts.
     */
    public AdminSession login(String username, String password, AdminRole requiredRole) {
        Admin admin = adminService.authenticate(username, password);

        if (requiredRole != null && admin.getRole() != requiredRole) {
            throw new ApiException(
                    "This login is for " + requiredRole + " accounts only",
                    HttpStatus.FORBIDDEN);
        }

        String token = CodeGenerator.generateToken();
        LocalDateTime now = LocalDateTime.now();
        AdminSession session = new AdminSession(token, admin.getUsername(), admin.getRole(), now, now.plusHours(sessionDurationHours));
        adminSessionRepository.save(session);
        return session;
    }

    public void logout(String token) {
        AdminSession session = adminSessionRepository.findByToken(token)
                .orElseThrow(() -> new ApiException("Session not found", HttpStatus.NOT_FOUND));
        session.setActive(false);
        adminSessionRepository.save(session);
    }

    public AdminSession validate(String token) {
        AdminSession session = adminSessionRepository.findByToken(token)
                .orElseThrow(() -> new ApiException("Invalid or missing admin session token", HttpStatus.UNAUTHORIZED));

        if (!session.isActive()) {
            throw new ApiException("Admin session has been logged out", HttpStatus.UNAUTHORIZED);
        }
        if (session.isExpired()) {
            session.setActive(false);
            adminSessionRepository.save(session);
            throw new ApiException("Admin session expired, please log in again", HttpStatus.UNAUTHORIZED);
        }

        // Re-check the admin hasn't been deactivated mid-session
        Admin admin = adminService.getByUsername(session.getUsername());
        if (!admin.isActive()) {
            session.setActive(false);
            adminSessionRepository.save(session);
            throw new ApiException("This admin account has been deactivated", HttpStatus.FORBIDDEN);
        }

        return session;
    }
}
