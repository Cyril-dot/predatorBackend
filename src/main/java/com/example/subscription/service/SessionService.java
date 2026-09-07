package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.Session;
import com.example.subscription.model.UserAccount;
import com.example.subscription.repository.InMemorySessionRepository;
import com.example.subscription.repository.InMemoryUserRepository;
import com.example.subscription.util.CodeGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SessionService {

    private final InMemoryUserRepository userRepository;
    private final InMemorySessionRepository sessionRepository;

    public SessionService(InMemoryUserRepository userRepository, InMemorySessionRepository sessionRepository) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
    }

    /**
     * Logs a user in.
     * - Rejects if credentials are wrong.
     * - Rejects if the account's total purchased time window has already elapsed.
     * - Rejects if the account already has an active session elsewhere
     *   (this is what stops the password from being shared/used by two people
     *   at once).
     * - Starts the usage-time countdown on the very first login.
     */
    public synchronized Session login(String username, String password, String ipAddress) {
        UserAccount account = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("Invalid username or password", HttpStatus.UNAUTHORIZED));

        if (!account.isPaid()) {
            throw new ApiException(
                    "This account has not paid for a plan yet. Please subscribe first.",
                    HttpStatus.FORBIDDEN);
        }

        if (!account.getPassword().equals(password)) {
            throw new ApiException("Invalid username or password", HttpStatus.UNAUTHORIZED);
        }

        // Reclaim/expire any stale session first
        expireIfNeeded(account);

        if (account.isSubscriptionConsumed() || account.isWindowExpired()) {
            throw new ApiException("Your subscription time has expired. Please pay again to continue.", HttpStatus.FORBIDDEN);
        }

        // Enforce single active session per account
        if (account.getActiveSessionToken() != null) {
            Session existing = sessionRepository.findByToken(account.getActiveSessionToken()).orElse(null);
            if (existing != null && existing.isActive() && !existing.isExpired()) {
                throw new ApiException(
                        "This account is already logged in on another device. Log out there first.",
                        HttpStatus.CONFLICT);
            }
        }

        // Start the countdown on first login only
        if (!account.hasStartedUsage()) {
            account.setUsageExpiresAt(LocalDateTime.now().plusHours(account.getPlan().getHours()));
        }

        String token = CodeGenerator.generateToken();
        // A session can never outlive the account's overall usage window
        LocalDateTime sessionExpiry = account.getUsageExpiresAt();

        Session session = new Session(token, username, LocalDateTime.now(), sessionExpiry, ipAddress);
        sessionRepository.save(session);

        account.setActiveSessionToken(token);
        userRepository.save(account);

        return session;
    }

    public synchronized void logout(String token) {
        Session session = sessionRepository.findByToken(token)
                .orElseThrow(() -> new ApiException("Session not found", HttpStatus.NOT_FOUND));

        session.setActive(false);
        sessionRepository.save(session);

        userRepository.findByUsername(session.getUsername()).ifPresent(account -> {
            if (token.equals(account.getActiveSessionToken())) {
                account.setActiveSessionToken(null);
                // If the whole purchased window has also passed, mark consumed
                if (account.isWindowExpired()) {
                    account.setSubscriptionConsumed(true);
                }
                userRepository.save(account);
            }
        });
    }

    /**
     * Validates a session token for use by protected endpoints.
     * Automatically force-logs-out the session if its time has elapsed.
     */
    public synchronized Session validate(String token) {
        Session session = sessionRepository.findByToken(token)
                .orElseThrow(() -> new ApiException("Invalid or missing session token", HttpStatus.UNAUTHORIZED));

        if (!session.isActive()) {
            throw new ApiException("Session has been logged out", HttpStatus.UNAUTHORIZED);
        }

        if (session.isExpired()) {
            forceLogout(session);
            throw new ApiException("Your time limit has been reached. You have been logged out.", HttpStatus.UNAUTHORIZED);
        }

        return session;
    }

    /** Used by the scheduled cleanup job. */
    public synchronized void forceLogout(Session session) {
        session.setActive(false);
        sessionRepository.save(session);

        userRepository.findByUsername(session.getUsername()).ifPresent(account -> {
            if (session.getToken().equals(account.getActiveSessionToken())) {
                account.setActiveSessionToken(null);
            }
            if (account.isWindowExpired()) {
                account.setSubscriptionConsumed(true);
            }
            userRepository.save(account);
        });
    }

    private void expireIfNeeded(UserAccount account) {
        if (account.getActiveSessionToken() == null) {
            return;
        }
        sessionRepository.findByToken(account.getActiveSessionToken()).ifPresent(session -> {
            if (session.isExpired() && session.isActive()) {
                forceLogout(session);
            }
        });
    }
}
