package com.example.subscription.filter;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.AdminRole;
import com.example.subscription.model.AdminSession;
import com.example.subscription.service.AdminAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guards every request under /api/admin/** and /api/superadmin/**.
 * Expects header: Authorization: Bearer <admin-session-token>
 *
 * - /api/admin/**      -> requires ADMIN or SUPER_ADMIN
 * - /api/superadmin/** -> requires SUPER_ADMIN only
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private final AdminAuthService adminAuthService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminAuthFilter(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Auth endpoints (login/logout) for both admin and super admin must
        // stay open - login is how you get a token in the first place, and
        // logout does its own token lookup.
        if (uri.startsWith("/api/admin/auth") || uri.startsWith("/api/superadmin/auth")) {
            return true;
        }
        return !(uri.startsWith("/api/admin") || uri.startsWith("/api/superadmin"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer "))
                ? header.substring(7)
                : null;

        if (token == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing Authorization Bearer token");
            return;
        }

        try {
            AdminSession session = adminAuthService.validate(token);

            boolean isSuperAdminPath = request.getRequestURI().startsWith("/api/superadmin");
            if (isSuperAdminPath && session.getRole() != AdminRole.SUPER_ADMIN) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "Super admin access required");
                return;
            }

            request.setAttribute("adminSession", session);
            filterChain.doFilter(request, response);
        } catch (ApiException ex) {
            writeError(response, ex.getStatus().value(), ex.getMessage());
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        body.put("data", null);

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
