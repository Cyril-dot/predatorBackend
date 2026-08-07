package com.example.subscription.config;

import com.example.subscription.model.AdminRole;
import com.example.subscription.repository.InMemoryAdminRepository;
import com.example.subscription.service.AdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * On startup, ensures there is always at least one SUPER_ADMIN account to
 * log in with (since there's no public "create super admin" endpoint -
 * only an existing super admin can create more admins/super admins).
 *
 * Configure the bootstrap credentials in application.properties:
 *   superadmin.bootstrap.username=superadmin@platform.com
 *   superadmin.bootstrap.password=ChangeMe123!
 *
 * CHANGE THESE before deploying anywhere real.
 */
@Component
public class SuperAdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final AdminService adminService;
    private final InMemoryAdminRepository adminRepository;

    @Value("${superadmin.bootstrap.username}")
    private String bootstrapUsername;

    @Value("${superadmin.bootstrap.password}")
    private String bootstrapPassword;

    public SuperAdminBootstrap(AdminService adminService, InMemoryAdminRepository adminRepository) {
        this.adminService = adminService;
        this.adminRepository = adminRepository;
    }

    @Override
    public void run(String... args) {
        if (adminRepository.exists(bootstrapUsername)) {
            return;
        }
        adminService.createAdmin(bootstrapUsername, bootstrapPassword, AdminRole.SUPER_ADMIN, null);
        log.info("=======================================================");
        log.info(" Bootstrap SUPER_ADMIN created");
        log.info(" username: {}", bootstrapUsername);
        log.info(" (password as configured in application.properties)");
        log.info(" CHANGE THESE CREDENTIALS before deploying to production");
        log.info("=======================================================");
    }
}
