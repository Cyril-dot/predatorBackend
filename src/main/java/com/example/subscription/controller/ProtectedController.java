package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.model.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything under /api/protected/** is guarded by SessionAuthFilter, which
 * rejects requests with no/expired/invalid session tokens and auto-logs-out
 * users whose time limit has passed.
 */
@RestController
@RequestMapping("/api/protected")
public class ProtectedController {

    @GetMapping("/status")
    public ApiResponse<Object> status(HttpServletRequest request) {
        Session session = (Session) request.getAttribute("session");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", session.getUsername());
        result.put("loginAt", session.getLoginAt());
        result.put("expiresAt", session.getExpiresAt());
        result.put("secondsRemaining", session.secondsRemaining());

        return ApiResponse.ok("Session active", result);
    }

    @GetMapping("/dashboard")
    public ApiResponse<Object> dashboard(HttpServletRequest request) {
        Session session = (Session) request.getAttribute("session");
        return ApiResponse.ok("Welcome " + session.getUsername(), Map.of(
                "secondsRemaining", session.secondsRemaining()
        ));
    }
}
