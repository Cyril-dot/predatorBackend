package com.example.subscription.util;

import java.security.SecureRandom;
import java.util.UUID;

public class CodeGenerator {

    private static final String ALPHA_NUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private CodeGenerator() {
    }

    /** Generates a random, human-typeable password of the given length. */
    public static String generatePassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHA_NUMERIC.charAt(RANDOM.nextInt(ALPHA_NUMERIC.length())));
        }
        return sb.toString();
    }

    /** Generates a unique Paystack transaction reference. */
    public static String generateReference() {
        return "SUB_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Generates a session token. */
    public static String generateToken() {
        return UUID.randomUUID().toString().replace("-", "") +
                UUID.randomUUID().toString().replace("-", "");
    }

    /** Generates a short, shareable referral code, e.g. REF-8K3PQZ. */
    public static String generateReferralCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHA_NUMERIC.charAt(RANDOM.nextInt(ALPHA_NUMERIC.length())));
        }
        return "REF-" + sb.toString().toUpperCase();
    }

    /** Generates a unique id for commission/payout records. */
    public static String generateId() {
        return UUID.randomUUID().toString();
    }
}
