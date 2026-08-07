package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.Plan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Handles Paystack payments for subscriptions: hosted Card checkout
 * (existing) and Ghanaian Mobile Money direct charge (new).
 *
 * ── Card flow (hosted checkout) ─────────────────────────────────────────────
 *   initializeTransaction() -> POST /transaction/initialize -> redirect user
 *   verifyTransaction()     -> GET  /transaction/verify/:reference (fallback)
 *
 * ── Mobile Money flow (per Paystack docs, same pattern as the wallet deposit
 *    controller) ───────────────────────────────────────────────────────────
 *   Step 1 — chargeMomo(): POST /charge with mobile_money { phone, provider }.
 *     Inspect data.status:
 *       "pay_offline" — MTN dispatches a USSD flash / push prompt. No further
 *                       action; wait for webhook.
 *       "send_otp"    — collect OTP from the user, call submitOtp().
 *       "success"     — charged immediately (rare).
 *       "failed"      — charge was declined.
 *   Step 2 — submitOtp(): only when data.status == "send_otp".
 *
 *   Wallet/subscription activation should only ever happen from the webhook
 *   (or a verify call used as fallback) — never directly off these responses.
 *
 * Ghana MoMo providers: mtn | atl | vod
 * Amount unit: pesewas (GHS 1.00 = 100 pesewas) — Plan already stores this.
 * Phone format sent to Paystack: local 0XXXXXXXXX (10 digits)
 */
@Service
public class PaystackService {

    private static final Set<String> VALID_GH_PROVIDERS = Set.of("mtn", "atl", "vod");

    private final Duration paystackTimeout       = Duration.ofSeconds(10);
    private final long     paystackRetryAttempts = 2;

    @Value("${paystack.secret-key}")
    private String secretKey;

    @Value("${paystack.base-url}")
    private String baseUrl;

    @Value("${paystack.callback-url}")
    private String callbackUrl;

    private final WebClient.Builder webClientBuilder;

    public PaystackService(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    // ─── Card: hosted checkout ──────────────────────────────────────────────

    /**
     * Initializes a Paystack transaction and returns the "data" object,
     * which includes authorization_url — the frontend should redirect the
     * user's browser there.
     */
    public Map<String, Object> initializeTransaction(String email, Plan plan, String reference) {
        Map<String, Object> body = Map.of(
                "email",        email,
                "amount",       plan.getAmountInPesewas(),
                "reference",    reference,
                "callback_url", callbackUrl
        );
        return postToPaystack("/transaction/initialize", body, "initializeTransaction");
    }

    /**
     * Verifies a transaction by reference. Returns the "data" object from
     * Paystack's response (status, amount, customer, etc). Fallback only —
     * the webhook remains the source of truth for activating a subscription.
     */
    public Map<String, Object> verifyTransaction(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new ApiException("reference is required.", HttpStatus.BAD_REQUEST);
        }
        return getFromPaystack("/transaction/verify/" + reference, "verifyTransaction");
    }

    // ─── Mobile Money: Step 1 — charge ──────────────────────────────────────

    /**
     * Calls Paystack POST /charge with a mobile_money payload for the given
     * plan. Returns the "data" object — inspect data.get("status"):
     *   "pay_offline" -> tell user to check their phone for a push/USSD prompt
     *   "send_otp"    -> collect OTP, then call submitOtp()
     *   "success"     -> immediate success (rare)
     *   "failed"      -> show failure message
     */
    public Map<String, Object> chargeMomo(String email, Plan plan, String rawPhone,
                                          String rawProvider, String reference) {
        var phone = normalizeGhanaPhone(rawPhone);

        if (rawProvider == null) {
            throw new ApiException("provider is required. Use one of: mtn, atl, vod.", HttpStatus.BAD_REQUEST);
        }
        var provider = rawProvider.trim().toLowerCase();
        if (!VALID_GH_PROVIDERS.contains(provider)) {
            throw new ApiException(
                    "Unsupported provider '" + provider + "'. Use one of: mtn, atl, vod.",
                    HttpStatus.BAD_REQUEST);
        }

        Map<String, Object> body = Map.of(
                "email",        email,
                "amount",       plan.getAmountInPesewas(),
                "currency",     "GHS",
                "reference",    reference,
                "mobile_money", Map.of("phone", phone, "provider", provider)
        );
        return postToPaystack("/charge", body, "chargeMomo");
    }

    // ─── Mobile Money: Step 2 — submit OTP ──────────────────────────────────

    /**
     * Calls Paystack POST /charge/submit_otp. Only call this when a prior
     * chargeMomo() returned data.status == "send_otp". Response has the same
     * data.status shape as chargeMomo(). Never activate a subscription here —
     * only the webhook does that.
     */
    public Map<String, Object> submitOtp(String otp, String reference) {
        if (otp == null || otp.isBlank()) {
            throw new ApiException("otp is required.", HttpStatus.BAD_REQUEST);
        }
        if (reference == null || reference.isBlank()) {
            throw new ApiException("reference is required.", HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> body = Map.of("otp", otp, "reference", reference);
        return postToPaystack("/charge/submit_otp", body, "submitOtp");
    }

    // ─── Shared Paystack call helpers ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> postToPaystack(String path, Map<String, Object> body, String callerTag) {
        Map<String, Object> result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + path)
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody ->
                        new ApiException("Paystack returned " + r.statusCode() + ": " + respBody,
                                HttpStatus.BAD_GATEWAY)))
                .bodyToMono(Map.class)
                .timeout(paystackTimeout)
                .retryWhen(Retry.max(paystackRetryAttempts)
                        .filter(ex -> !(ex instanceof ApiException)))
                .onErrorMap(ex -> !(ex instanceof ApiException),
                        ex -> new ApiException("Error contacting Paystack: " + ex.getMessage(),
                                HttpStatus.BAD_GATEWAY))
                .block();

        return unwrapData(result, path, callerTag);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getFromPaystack(String path, String callerTag) {
        Map<String, Object> result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + path)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody ->
                        new ApiException("Paystack returned " + r.statusCode() + ": " + respBody,
                                HttpStatus.BAD_GATEWAY)))
                .bodyToMono(Map.class)
                .timeout(paystackTimeout)
                .retryWhen(Retry.max(paystackRetryAttempts)
                        .filter(ex -> !(ex instanceof ApiException)))
                .onErrorMap(ex -> !(ex instanceof ApiException),
                        ex -> new ApiException("Error contacting Paystack: " + ex.getMessage(),
                                HttpStatus.BAD_GATEWAY))
                .block();

        return unwrapData(result, path, callerTag);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapData(Map<String, Object> result, String path, String callerTag) {
        if (result == null) {
            throw new ApiException("Paystack returned an empty response.", HttpStatus.BAD_GATEWAY);
        }
        if (!Boolean.TRUE.equals(result.get("status"))) {
            var msg = result.getOrDefault("message", "Paystack declined the request").toString();
            throw new ApiException("[" + callerTag + "] " + path + " -> " + msg, HttpStatus.BAD_GATEWAY);
        }
        var data = (Map<String, Object>) result.get("data");
        if (data == null) {
            throw new ApiException("[" + callerTag + "] " + path + " -> missing data field",
                    HttpStatus.BAD_GATEWAY);
        }
        return data;
    }

    // ─── Utilities ───────────────────────────────────────────────────────────

    /**
     * Normalizes any Ghana phone format to local 0XXXXXXXXX (10 digits).
     * Handles: +233XXXXXXXXX, 233XXXXXXXXX, 0XXXXXXXXX
     */
    private String normalizeGhanaPhone(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException("Phone number is required.", HttpStatus.BAD_REQUEST);
        }
        var digits = raw.trim().replaceAll("[\\s\\-]", "");

        if (digits.startsWith("+233")) {
            digits = "0" + digits.substring(4);
        } else if (digits.startsWith("233") && digits.length() == 12) {
            digits = "0" + digits.substring(3);
        }

        if (!digits.matches("^0\\d{9}$")) {
            throw new ApiException(
                    "Invalid Ghana phone number. Expected format: 0XXXXXXXXX or +233XXXXXXXXX.",
                    HttpStatus.BAD_REQUEST);
        }
        return digits;
    }
}