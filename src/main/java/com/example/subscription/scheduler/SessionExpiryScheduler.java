package com.example.subscription.scheduler;

import com.example.subscription.model.Session;
import com.example.subscription.repository.InMemorySessionRepository;
import com.example.subscription.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SessionExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryScheduler.class);

    private final InMemorySessionRepository sessionRepository;
    private final SessionService sessionService;

    public SessionExpiryScheduler(InMemorySessionRepository sessionRepository, SessionService sessionService) {
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
    }

    /**
     * Runs periodically (see session.cleanup.interval-ms) and force logs-out
     * any session whose time limit has been reached, even if the user never
     * makes another request.
     */
    @Scheduled(fixedDelayString = "${session.cleanup.interval-ms:30000}")
    public void expireStaleSessions() {
        for (Session session : sessionRepository.findAll()) {
            if (session.isActive() && session.isExpired()) {
                log.info("Auto-logging out user '{}' - time limit reached", session.getUsername());
                sessionService.forceLogout(session);
            }
        }
    }
}
