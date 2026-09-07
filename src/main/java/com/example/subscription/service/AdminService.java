package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.Admin;
import com.example.subscription.model.AdminRole;
import com.example.subscription.repository.InMemoryAdminRepository;
import com.example.subscription.util.CodeGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminService {

    private final InMemoryAdminRepository adminRepository;

    @Value("${referral.default-admin-share-percent:30}")
    private int defaultAdminSharePercent;

    @Value("${referral.default-platform-share-percent:70}")
    private int defaultPlatformSharePercent;

    public AdminService(InMemoryAdminRepository adminRepository) {
        this.adminRepository = adminRepository;
    }

    /** Only super admins call this (enforced at the controller/filter level). */
    public synchronized Admin createAdmin(String email, String password, AdminRole role, Integer adminSharePercentOverride) {
        if (adminRepository.exists(email)) {
            throw new ApiException("An admin with this email already exists", HttpStatus.CONFLICT);
        }

        int adminShare = (adminSharePercentOverride != null) ? adminSharePercentOverride : defaultAdminSharePercent;
        if (adminShare < 0 || adminShare > 100) {
            throw new ApiException("adminSharePercent must be between 0 and 100", HttpStatus.BAD_REQUEST);
        }
        int platformShare = 100 - adminShare;

        String referralCode = generateUniqueReferralCode();

        Admin admin = new Admin(email, password, role, referralCode, adminShare, platformShare);
        adminRepository.save(admin);
        return admin;
    }

    public Admin authenticate(String username, String password) {
        Admin admin = adminRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("Invalid admin credentials", HttpStatus.UNAUTHORIZED));

        if (!admin.isActive()) {
            throw new ApiException("This admin account has been deactivated", HttpStatus.FORBIDDEN);
        }

        if (!admin.getPassword().equals(password)) {
            throw new ApiException("Invalid admin credentials", HttpStatus.UNAUTHORIZED);
        }

        return admin;
    }

    public Admin getByUsername(String username) {
        return adminRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("Admin not found", HttpStatus.NOT_FOUND));
    }

    public Admin getByReferralCode(String code) {
        return adminRepository.findByReferralCode(code)
                .orElseThrow(() -> new ApiException("Invalid referral code", HttpStatus.BAD_REQUEST));
    }

    public List<Admin> listAll() {
        return List.copyOf(adminRepository.findAll());
    }

    public synchronized Admin setActive(String username, boolean active) {
        Admin admin = getByUsername(username);
        admin.setActive(active);
        return admin;
    }

    private String generateUniqueReferralCode() {
        String code;
        int attempts = 0;
        do {
            code = CodeGenerator.generateReferralCode();
            attempts++;
            if (attempts > 20) {
                throw new ApiException("Could not generate a unique referral code, try again", HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } while (adminRepository.referralCodeExists(code));
        return code;
    }
}
