package com.example.subscription.filter;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.Session;
import com.example.subscription.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Guards every request under /api/protected/**.
 * Expects header: Authorization: Bearer <session-token>
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    private final SessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionAuthFilter(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/protected");
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
            Session session = sessionService.validate(token);
            request.setAttribute("session", session);
            filterChain.doFilter(request, response);
        } catch (ApiException ex) {
            writeError(response, ex.getStatus().value(), ex.getMessage());
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        body.put("data", null);

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
