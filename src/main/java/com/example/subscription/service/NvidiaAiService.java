package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.PickPrediction;
import com.example.subscription.model.ScanPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Calls vision-capable chat completion endpoints across multiple free-tier
 * providers to analyze a betting-slip image and produce per-pick predictions.
 *
 * ============================================================================
 * PROVIDER ORDER - SPEED-RANKED FROM MEASURED BENCHMARKS (2026-09-07)
 * ============================================================================
 * This chain is ordered by actual measured response time on a same-sized test
 * image with the same prompt, not by theoretical throughput or vendor claims.
 * Full bench data and methodology: see free-vision-models.md written alongside
 * this service. Re-benchmark periodically - these numbers drift as providers
 * change their infra and as upstream models get swapped underneath a free
 * router (this happened at least once during benchmarking: OpenRouter's
 * "openrouter/free" auto-router silently changed which model answered between
 * two calls in the same session).
 *
 *   1. OPENROUTER   https://openrouter.ai/api/v1          <- fastest measured (1.01s, dots-studio/dots-3-note-preview:free)
 *   2. GEMINI        generativelanguage.googleapis.com     <- 1.42s (gemini-3.5-flash-lite), MOST RELIABLE: zero failures across every model tested
 *   3. CLOUDFLARE    api.cloudflare.com/.../ai/run (proxied) <- 2.11s (llama-3.2-11b-vision-instruct), reliable once request shapes fixed
 *
 * NVIDIA (build.nvidia.com / integrate.api.nvidia.com) is DELIBERATELY EXCLUDED
 * from this chain. Every model hand-picked from NVIDIA's own catalog search
 * results - checked days to weeks before use - returned HTTP 410 (retired) or
 * 404 (not found) on first real call, including two models that both died on
 * the exact same day. This is not a "wrong model id" problem: NVIDIA's own
 * developer forums have an open, unresolved thread of other developers hitting
 * this identical issue with no clean way to query which models are currently
 * live. Do not add NVIDIA back into this chain without first confirming
 * NVIDIA has shipped a reliable "list only working models" endpoint - as of
 * this writing they have not.
 *
 * Groq and Cerebras are excluded from the vision chain entirely: neither has
 * a dependable free vision-capable model as of this writing (Groq's Qwen VL
 * preview is unstable per the GROQ NOTE further down; Cerebras has no
 * confirmed vision model on its free tier at all).
 *
 * ============================================================================
 * MODEL CATALOG LAST VERIFIED: 2026-09-07. Free-tier vision model rosters
 * change on the order of weeks, sometimes days (see the NVIDIA note above for
 * the most extreme case observed). ALWAYS check the live catalog before
 * trusting the model list below:
 *   - OpenRouter:  openrouter.ai/collections/free-models (filter for vision/image input)
 *   - Gemini:      ai.google.dev/gemini-api/docs/models (check the changelog for shutdown dates)
 *   - Cloudflare:  developers.cloudflare.com/workers-ai/models/
 * ============================================================================
 *
 * OPENROUTER NOTE: model ids are pulled from OpenRouter's official "Free AI
 * Models" collection, filtered to entries whose description explicitly
 * mentions image input. "openrouter/free" is OpenRouter's own auto-router: it
 * picks a random free model that supports the request's required features
 * (image input, in this case) rather than a fixed model. That means the
 * SAME model id can return DIFFERENT underlying models between calls - this
 * was observed directly during benchmarking. Treat "openrouter/free" as a
 * single opportunistic attempt, not a guaranteed specific model; the named
 * fallback models below it exist so the chain has a stable, known-good
 * second attempt on this provider before moving to Gemini.
 *
 * GEMINI NOTE: gemini-2.5-flash and gemini-2.5-flash-lite are RETIRED for new
 * users as of this writing - Google's own error message on a 404-equivalent
 * response names the exact replacements, which are already reflected below
 * (gemini-3.6-flash, gemini-3.5-flash-lite). gemini-3-flash-preview is a
 * "thinking" model: max_output_tokens is a COMBINED budget for internal
 * reasoning plus the visible answer, so a modest token budget can silently
 * truncate the answer before it starts. This service gives thinking models a
 * multiplied token budget and forces thinkingConfig.thinkingLevel=low - see
 * isThinkingModel() and buildGeminiGenerationConfig().
 *
 * GEMINI MULTI-KEY NOTE: exactly like the original Groq/Gemini multi-key
 * pattern this class already used, ai.gemini.api-keys accepts a
 * comma-separated list. Each key gets its own independent free-tier quota and
 * its own attempt in the chain (gemini-key1, gemini-key2, ...) before the
 * chain falls through to the next provider. This is now true for EVERY
 * provider, not just Gemini - see ACCOUNT ROTATION below.
 *
 * CLOUDFLARE NOTE: api.cloudflare.com does NOT send CORS headers, which only
 * matters for browser callers - irrelevant for this server-side service, but
 * documented here because it surprised us during the browser-based benchmark
 * tool build and is worth knowing if this logic is ever ported to a frontend.
 * Cloudflare also uses THREE DIFFERENT request body shapes depending on the
 * model family, unlike every other provider in this chain which is OpenAI-
 * compatible:
 *   - llama-vision:   top-level { messages, image: base64String }
 *   - openai-vision:  OpenAI-style { messages: [...content: [{image_url}]] }
 *   - moondream:      { task, image: byteArray, question, max_tokens }
 * See buildCloudflareBody() for the per-shape branching. Cloudflare's free
 * tier is a single 10,000-Neuron/day budget SHARED ACROSS ALL MODELS AND ALL
 * MODALITIES on an account - unlike the other providers, adding more
 * Cloudflare accounts to the rotation is the only way to raise this ceiling,
 * since there's no separate per-model allowance to exhaust first.
 *
 * ============================================================================
 * ACCOUNT ROTATION (NEW)
 * ============================================================================
 * Every provider below supports up to 4 accounts (api keys), configured as a
 * comma-separated list via ai.<provider>.api-keys. Within a single provider,
 * a 429 (rate limited) or 402 (quota exhausted) response on one account
 * immediately retries the SAME model on the NEXT account before the chain
 * gives up on that provider and moves to the next one. This is the same
 * pattern the original file used for Groq - it's now applied uniformly to
 * OpenRouter, Gemini, and Cloudflare as well, since all three can hit
 * account-level limits on a busy scan queue.
 *
 * Attempt chain shape with full rotation (4 accounts x 3 providers = up to 12
 * attempts before total failure, though most scans succeed on attempt 1-2):
 *   openrouter-key1, openrouter-key2, openrouter-key3, openrouter-key4,
 *   gemini-key1,     gemini-key2,     gemini-key3,     gemini-key4,
 *   cloudflare-key1, cloudflare-key2, cloudflare-key3, cloudflare-key4
 *
 * Configure as many keys per provider as you have (up to 4 recommended - see
 * class javadoc note on why 4: it matches the sign-up cost/effort sweet spot
 * for free-tier accounts without becoming unmanageable to provision).
 *
 * Providers whose API key(s) are blank are SKIPPED, so you can deploy with
 * only one provider configured (though having all three is recommended so
 * providers can cover each other's outages/rate limits).
 *
 * LOGGING: every scan gets a short trace id (MDC key "scanId") that prefixes
 * all log lines for that request, so concurrent scans stay untangled in the
 * log file. Each attempt logs the outbound request summary, HTTP status,
 * latency, token usage, finish reason, and a preview of the returned content.
 * A summary table of all attempts is printed at the end whether the scan
 * succeeded or failed. API keys are always masked; the base64 image is never
 * logged.
 */
@Service
public class NvidiaAiService {

    private static final Logger log = LoggerFactory.getLogger(NvidiaAiService.class);
    /** Separate logger so raw request/response payloads can be toggled independently. */
    private static final Logger wire = LoggerFactory.getLogger(NvidiaAiService.class.getName() + ".wire");

    private static final String MDC_SCAN_ID = "scanId";

    /** Max accounts (api keys) supported per provider in the rotation. */
    private static final int MAX_ACCOUNTS_PER_PROVIDER = 4;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebClient.Builder webClientBuilder;
    private final Environment env;

    /**
     * Per-attempt timeout. A vision model producing 1-2k tokens of JSON with
     * stream=false routinely needs 5-30s depending on provider/model, so a
     * short default guarantees premature timeouts especially on Cloudflare's
     * larger models (Mistral Small 3.1, Qwen 3.8 27B measured at 6-8s) and
     * NVIDIA-class large models generally (excluded from this chain, but the
     * lesson generalizes: give large VLMs real time to answer).
     */
    @Value("${ai.attempt-timeout-seconds:60}")
    private long attemptTimeoutSeconds;

    /**
     * Global default max_tokens, used for any provider/model that doesn't
     * have its own override. See GEMINI NOTE above for why thinking models
     * get a multiplied budget instead of this flat default.
     */
    @Value("${ai.max-tokens:512}")
    private int defaultMaxTokens;

    // ---- image prep -----------------------------------------------------

    @Value("${ai.image.max-edge-px:1024}")
    private int maxEdgePx;

    @Value("${ai.image.max-base64-bytes:180000}")
    private int maxBase64Bytes;

    @Value("${ai.image.jpeg-quality:0.75}")
    private float jpegQuality;

    // ---- logging switches ------------------------------------------------

    @Value("${ai.log.full-response:false}")
    private boolean logFullResponse;

    @Value("${ai.log.request-body:false}")
    private boolean logRequestBody;

    @Value("${ai.log.preview-chars:400}")
    private int previewChars;

    public NvidiaAiService(WebClient.Builder webClientBuilder, Environment env) {
        this.webClientBuilder = webClientBuilder;
        this.env = env;
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    /** Which request/response shape a provider+model combination needs. */
    private enum BodyShape {
        OPENAI_VISION,   // {messages:[{role,content:[{type:text},{type:image_url,image_url:{url}}]}]}
        GEMINI_NATIVE,   // {contents:[{parts:[{inline_data},{text}]}], generationConfig}
        CF_LLAMA_VISION, // {messages, image: base64String}
        CF_MOONDREAM     // {task, image: byteArray, question, max_tokens}
    }

    public record Provider(String id, String baseUrl, String apiKey, List<String> models,
                            int maxTokens, BodyShape shape) {
    }

    private record Attempt(Provider provider, String model, int accountIndex, int accountCount) {
        String label() {
            String acctSuffix = accountCount > 1 ? "#" + (accountIndex + 1) : "";
            return provider.id() + acctSuffix + "/" + model;
        }
    }

    /** Per-attempt outcome, collected for the end-of-scan summary table. */
    private static final class AttemptResult {
        String label;
        boolean success;
        long millis;
        String detail;
        Integer promptTokens;
        Integer completionTokens;
        String finishReason;
        int picks;
    }

    public static class ScanAnalysis {
        public int totalPicksDetected;
        public List<PickPrediction> predictions = new ArrayList<>();
        public String rawModelOutput;   // populated only if JSON parsing failed
        public String modelUsed;        // which model actually answered
        public String providerUsed;     // which provider it came from
        public String scanId;           // trace id, matches the log lines
    }

    // ------------------------------------------------------------------
    // Public entry point (signature unchanged)
    // ------------------------------------------------------------------

    public ScanAnalysis analyzeSlip(String imageBase64, String imageMediaType, ScanPlan plan) {

        String scanId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_SCAN_ID, scanId);
        long scanStarted = System.currentTimeMillis();

        try {
            log.info("=== SCAN START id={} maxPicks={} fullCoverage={} ===",
                    scanId,
                    plan.isFullCoverage() ? "unlimited" : plan.getMaxPicks(),
                    plan.isFullCoverage());

            List<Attempt> attempts = buildAttemptChain();

            if (attempts.isEmpty()) {
                log.error("No usable provider: every provider's API key(s) are missing.");
                throw new ApiException(
                        "AI scanning is not configured: no provider API keys are set " +
                        "(checked ai.openrouter.api-keys, ai.gemini.api-keys, ai.cloudflare.api-keys).",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }

            log.info("Attempt chain ({} attempts, {}s timeout each, speed-ranked OpenRouter -> Gemini -> Cloudflare):",
                    attempts.size(), attemptTimeoutSeconds);
            for (int i = 0; i < attempts.size(); i++) {
                Attempt a = attempts.get(i);
                log.info("  [{}/{}] {} -> {} (key {}, max_tokens {})",
                        i + 1, attempts.size(), a.label(), a.provider().baseUrl(),
                        mask(a.provider().apiKey()), a.provider().maxTokens());
            }

            String[] prepared = prepareImage(imageBase64, imageMediaType);
            String dataUri = "data:" + prepared[1] + ";base64," + prepared[0];

            List<AttemptResult> results = new ArrayList<>();

            for (int i = 0; i < attempts.size(); i++) {
                Attempt attempt = attempts.get(i);
                AttemptResult ar = new AttemptResult();
                ar.label = attempt.label();
                long started = System.currentTimeMillis();

                try {
                    log.info(">>> ATTEMPT {}/{} [{}] POST {} (max_tokens={})",
                            i + 1, attempts.size(), attempt.label(), attempt.provider().baseUrl(),
                            attempt.provider().maxTokens());

                    String content = callProvider(dataUri, prepared[1], attempt, plan, ar);

                    ScanAnalysis analysis = parseModelResponse(content, plan, attempt.label());

                    if (analysis.predictions.isEmpty() && analysis.rawModelOutput != null) {
                        throw new ApiException("[" + attempt.label() + "] returned unparseable output: " +
                                truncate(analysis.rawModelOutput, 300), HttpStatus.BAD_GATEWAY);
                    }

                    ar.success = true;
                    ar.millis = System.currentTimeMillis() - started;
                    ar.picks = analysis.predictions.size();
                    ar.detail = "OK";
                    results.add(ar);

                    analysis.providerUsed = attempt.provider().id();
                    analysis.modelUsed = attempt.model();
                    analysis.scanId = scanId;

                    log.info("<<< SUCCESS [{}] {}ms, {} pick(s) of {} detected",
                            attempt.label(), ar.millis, analysis.predictions.size(),
                            analysis.totalPicksDetected);
                    logSummary(results, attempts.size(), System.currentTimeMillis() - scanStarted, true);
                    return analysis;

                } catch (Exception ex) {
                    ar.success = false;
                    ar.millis = System.currentTimeMillis() - started;
                    ar.detail = ex.getClass().getSimpleName() + ": " + rootMessage(ex);
                    results.add(ar);

                    log.warn("<<< FAILED [{}] after {}ms: {}", attempt.label(), ar.millis, ar.detail);
                    log.debug("Full stack trace for [{}]", attempt.label(), ex);
                }
            }

            logSummary(results, attempts.size(), System.currentTimeMillis() - scanStarted, false);

            String failureList = results.stream()
                    .map(r -> r.label + " (" + r.millis + "ms) -> " + r.detail)
                    .collect(Collectors.joining("\n"));

            throw new ApiException(
                    "AI scanning failed. All " + attempts.size() + " attempt(s) failed across " +
                    "OpenRouter, Gemini, and Cloudflare accounts:\n" + failureList,
                    HttpStatus.BAD_GATEWAY);

        } finally {
            MDC.remove(MDC_SCAN_ID);
        }
    }

    /** Prints an aligned table of every attempt so one glance explains the outcome. */
    private void logSummary(List<AttemptResult> results, int totalAttempts, long totalMs, boolean success) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== SCAN SUMMARY (").append(success ? "SUCCESS" : "ALL FAILED")
                .append(", ").append(totalMs).append("ms total, ")
                .append(results.size()).append('/').append(totalAttempts).append(" attempted) ===\n");
        sb.append(String.format("%-6s %-38s %-8s %-9s %-8s %s%n",
                "RESULT", "PROVIDER/MODEL", "TIME", "TOKENS", "FINISH", "DETAIL"));

        for (AttemptResult r : results) {
            String tokens = (r.promptTokens != null || r.completionTokens != null)
                    ? nz(r.promptTokens) + "/" + nz(r.completionTokens)
                    : "-";
            sb.append(String.format("%-6s %-38s %-8s %-9s %-8s %s%n",
                    r.success ? "OK" : "FAIL",
                    truncate(r.label, 38),
                    r.millis + "ms",
                    tokens,
                    r.finishReason == null ? "-" : r.finishReason,
                    truncate(r.detail, 160)));
        }
        sb.append("=".repeat(60));

        if (success) {
            log.info(sb.toString());
        } else {
            log.error(sb.toString());
        }
    }

    // ------------------------------------------------------------------
    // Provider / chain construction
    // ------------------------------------------------------------------

    /**
     * Builds the ordered attempt chain: OpenRouter first (fastest measured),
     * then Gemini (most reliable measured), then Cloudflare (slowest of the
     * three but still solid). Within each provider, every configured account
     * is tried against every configured model before moving to the next
     * provider. NVIDIA is intentionally not built here - see class javadoc.
     */
    private List<Attempt> buildAttemptChain() {
        List<Attempt> chain = new ArrayList<>();

        chain.addAll(buildOpenRouterAttempts());
        chain.addAll(buildGeminiAttempts());
        chain.addAll(buildCloudflareAttempts());

        return chain;
    }

    // ---- OpenRouter -------------------------------------------------

    private List<Attempt> buildOpenRouterAttempts() {
        List<Attempt> chain = new ArrayList<>();

        String baseUrl = env.getProperty("ai.openrouter.base-url", "https://openrouter.ai/api/v1");
        List<String> apiKeys = resolveAccountKeys("openrouter");
        if (apiKeys.isEmpty()) {
            log.warn("OpenRouter skipped: no ai.openrouter.api-keys configured");
            return chain;
        }

        List<String> models = new ArrayList<>();
        // Fastest measured model first (1.01s), then the auto-router as a
        // second attempt, then two more known-good vision models as final
        // fallbacks before this provider gives up.
        String primaryModel = env.getProperty("ai.openrouter.primary-model",
                "dots-studio/dots-3-note-preview:free");
        if (!isBlank(primaryModel)) {
            models.add(primaryModel);
        }
        String fallbacks = env.getProperty("ai.openrouter.fallback-models",
                "openrouter/free,nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free,minimax/minimax-m3:free");
        if (!isBlank(fallbacks)) {
            for (String m : splitCsv(fallbacks)) {
                if (!models.contains(m)) models.add(m);
            }
        }

        if (models.isEmpty()) {
            log.warn("OpenRouter skipped: no models configured");
            return chain;
        }

        int maxTokens = env.getProperty("ai.openrouter.max-tokens", Integer.class, defaultMaxTokens);

        for (int accountIdx = 0; accountIdx < apiKeys.size(); accountIdx++) {
            Provider provider = new Provider("openrouter", stripTrailingSlash(baseUrl),
                    apiKeys.get(accountIdx), models, maxTokens, BodyShape.OPENAI_VISION);
            for (String model : models) {
                chain.add(new Attempt(provider, model, accountIdx, apiKeys.size()));
            }
        }

        log.debug("Provider [openrouter] ENABLED: {} account(s), {} model(s) {}, max_tokens {}",
                apiKeys.size(), models.size(), models, maxTokens);
        return chain;
    }

    // ---- Gemini -------------------------------------------------------

    private List<Attempt> buildGeminiAttempts() {
        List<Attempt> chain = new ArrayList<>();

        String baseUrl = env.getProperty("ai.gemini.base-url",
                "https://generativelanguage.googleapis.com/v1beta/models");
        List<String> apiKeys = resolveAccountKeys("gemini");
        if (apiKeys.isEmpty()) {
            log.warn("Gemini skipped: no ai.gemini.api-keys configured");
            return chain;
        }

        List<String> models = new ArrayList<>();
        // Fastest + most reliable measured model first (gemini-3.5-flash-lite,
        // 1.42s, zero failures in testing), then the standard Flash tier, then
        // the "thinking" preview model last since it's slowest even at low
        // thinking effort. Old gemini-2.5-* ids are deliberately NOT used here
        // - they are retired for new users as of this writing (see GEMINI NOTE
        // in the class javadoc).
        String primaryModel = env.getProperty("ai.gemini.primary-model", "gemini-3.5-flash-lite");
        if (!isBlank(primaryModel)) models.add(primaryModel);

        String fallbacks = env.getProperty("ai.gemini.fallback-models",
                "gemini-3.6-flash,gemini-3-flash-preview");
        if (!isBlank(fallbacks)) {
            for (String m : splitCsv(fallbacks)) {
                if (!models.contains(m)) models.add(m);
            }
        }

        if (models.isEmpty()) {
            log.warn("Gemini skipped: no models configured");
            return chain;
        }

        int maxTokens = env.getProperty("ai.gemini.max-tokens", Integer.class, defaultMaxTokens);

        for (int accountIdx = 0; accountIdx < apiKeys.size(); accountIdx++) {
            Provider provider = new Provider("gemini", stripTrailingSlash(baseUrl),
                    apiKeys.get(accountIdx), models, maxTokens, BodyShape.GEMINI_NATIVE);
            for (String model : models) {
                chain.add(new Attempt(provider, model, accountIdx, apiKeys.size()));
            }
        }

        log.debug("Provider [gemini] ENABLED: {} account(s), {} model(s) {}, max_tokens {}",
                apiKeys.size(), models.size(), models, maxTokens);
        return chain;
    }

    // ---- Cloudflare -----------------------------------------------------

    private List<Attempt> buildCloudflareAttempts() {
        List<Attempt> chain = new ArrayList<>();

        // Cloudflare's REST API requires both an account id AND an api token
        // per account, unlike the other two providers which use a single key.
        // Encode each account as "accountId:apiToken" in the comma-separated
        // list, e.g. ai.cloudflare.accounts=acct1id:token1,acct2id:token2
        List<String> accounts = splitCsv(env.getProperty("ai.cloudflare.accounts", ""));
        if (accounts.isEmpty()) {
            log.warn("Cloudflare skipped: no ai.cloudflare.accounts configured " +
                    "(format: accountId:apiToken,accountId2:apiToken2)");
            return chain;
        }
        if (accounts.size() > MAX_ACCOUNTS_PER_PROVIDER) {
            log.warn("Cloudflare: {} accounts configured, only using the first {}",
                    accounts.size(), MAX_ACCOUNTS_PER_PROVIDER);
            accounts = accounts.subList(0, MAX_ACCOUNTS_PER_PROVIDER);
        }

        // Fastest measured model first (llama-3.2-11b-vision-instruct, 2.11s),
        // then llama-4-scout (2.44s, nearly tied, better-structured output in
        // testing), then the two slower models as final fallbacks. Note
        // llama-3.2-11b-vision-instruct requires a one-time license
        // acceptance per Cloudflare account before it will serve real
        // requests - see the ensureLlamaVisionLicenseAccepted() note below.
        List<String> models = new ArrayList<>();
        String primaryModel = env.getProperty("ai.cloudflare.primary-model",
                "@cf/meta/llama-3.2-11b-vision-instruct");
        if (!isBlank(primaryModel)) models.add(primaryModel);

        String fallbacks = env.getProperty("ai.cloudflare.fallback-models",
                "@cf/meta/llama-4-scout-17b-16e-instruct,@cf/mistralai/mistral-small-3.1-24b-instruct,@cf/qwen/qwen3.8-27b");
        if (!isBlank(fallbacks)) {
            for (String m : splitCsv(fallbacks)) {
                if (!models.contains(m)) models.add(m);
            }
        }

        if (models.isEmpty()) {
            log.warn("Cloudflare skipped: no models configured");
            return chain;
        }

        int maxTokens = env.getProperty("ai.cloudflare.max-tokens", Integer.class, defaultMaxTokens);

        for (int accountIdx = 0; accountIdx < accounts.size(); accountIdx++) {
            String[] parts = accounts.get(accountIdx).split(":", 2);
            if (parts.length != 2) {
                log.warn("Cloudflare account entry #{} malformed (expected accountId:apiToken), skipping",
                        accountIdx + 1);
                continue;
            }
            String accountId = parts[0].trim();
            String apiToken = parts[1].trim();
            String baseUrl = "https://api.cloudflare.com/client/v4/accounts/" + accountId + "/ai/run";

            for (String model : models) {
                BodyShape shape = shapeForCloudflareModel(model);
                Provider provider = new Provider("cloudflare", baseUrl, apiToken,
                        List.of(model), maxTokens, shape);
                chain.add(new Attempt(provider, model, accountIdx, accounts.size()));
            }
        }

        log.debug("Provider [cloudflare] ENABLED: {} account(s), {} model(s) {}, max_tokens {}",
                accounts.size(), models.size(), models, maxTokens);
        return chain;
    }

    /**
     * Cloudflare uses three different body shapes depending on model family
     * (see CLOUDFLARE NOTE in the class javadoc). Add new model->shape
     * mappings here as new Cloudflare vision models are adopted.
     */
    private BodyShape shapeForCloudflareModel(String model) {
        if (model.contains("moondream")) {
            return BodyShape.CF_MOONDREAM;
        }
        if (model.contains("llama-3.2") && model.contains("vision")) {
            return BodyShape.CF_LLAMA_VISION;
        }
        // llama-4-scout, mistral-small-3.1, qwen3.8 and other newer chat
        // models on Workers AI are OpenAI-compatible.
        return BodyShape.OPENAI_VISION;
    }

    // ------------------------------------------------------------------
    // Account key resolution (shared helper for OpenRouter / Gemini)
    // ------------------------------------------------------------------

    /**
     * Reads ai.<provider>.api-keys (comma-separated, up to
     * MAX_ACCOUNTS_PER_PROVIDER), falling back to the singular
     * ai.<provider>.api-key for a one-account deployment. This mirrors the
     * original file's Groq/Gemini multi-key pattern, generalized to every
     * OpenAI-style provider in this chain.
     */
    private List<String> resolveAccountKeys(String providerId) {
        String multi = env.getProperty("ai." + providerId + ".api-keys", "");
        List<String> keys = splitCsv(multi);

        if (keys.isEmpty()) {
            String single = env.getProperty("ai." + providerId + ".api-key", "");
            if (!isBlank(single)) {
                keys = List.of(single);
            }
        }

        if (keys.size() > MAX_ACCOUNTS_PER_PROVIDER) {
            log.warn("{}: {} accounts configured, only using the first {}",
                    providerId, keys.size(), MAX_ACCOUNTS_PER_PROVIDER);
            keys = keys.subList(0, MAX_ACCOUNTS_PER_PROVIDER);
        }

        return keys;
    }

    // ------------------------------------------------------------------
    // Request body construction
    // ------------------------------------------------------------------

    private String buildPrompt(ScanPlan plan) {
        String coverageInstruction = plan.isFullCoverage()
                ? "Analyze EVERY pick/game/section on the slip (full coverage)."
                : "The user's plan only covers up to " + plan.getMaxPicks() + " picks. " +
                  "Analyze at most the first " + plan.getMaxPicks() + " picks/sections on the slip, " +
                  "in the order they appear, and leave the rest out entirely.";

        return "You are looking at an image of a sports betting slip/coupon containing one or more " +
                "individual picks (each pick is one section of the slip: teams, market, odds).\n\n" +
                "1. Count and identify every distinct pick/section on the slip, in printed order.\n" +
                "2. " + coverageInstruction + "\n" +
                "3. For each analyzed pick give your own independent prediction (not just a restatement " +
                "of the slip), a confidence level, and a 1-3 sentence analysis.\n\n" +
                "Respond with ONLY a single JSON object matching exactly this shape:\n" +
                "{\n" +
                "  \"totalPicksDetected\": <integer, total picks found on the whole slip>,\n" +
                "  \"picks\": [\n" +
                "    {\n" +
                "      \"sectionIndex\": <integer, 1-based order on the slip>,\n" +
                "      \"matchLabel\": \"<teams/event as read off the slip>\",\n" +
                "      \"originalPick\": \"<the selection/market printed on the slip, if legible>\",\n" +
                "      \"prediction\": \"<1, X, 2, or unreadable>\",\n" +
                "      \"confidence\": \"High\" | \"Medium\" | \"Low\",\n" +
                "      \"analysis\": \"<brief reasoning>\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    private String systemPrompt() {
        return """
                You are Predator AI, an elite football betting analyst who reads virtual betting slips from images.

                Rules:
                - The image is a virtual football betting slip containing multiple fixtures.
                - Inspect the image and identify every visible match, in printed order.
                - For each fixture predict exactly ONE outcome: Home Win (1), Draw (X), or Away Win (2).
                - Base predictions on the odds shown, implied probabilities, recognizable team strength,
                  and football reasoning. Never just pick the lowest odds automatically. Consider upsets and draws.
                - If image quality prevents reading a fixture, set its prediction to "unreadable" rather than guessing.
                - Never fabricate fixtures or odds that are not visible in the image.

                Return ONLY a single valid JSON object. No markdown, no code fences, no text outside the JSON.
                """;
    }

    /** Gemini 3 "thinking" models spend part of max_output_tokens on invisible reasoning - see GEMINI NOTE. */
    private boolean isThinkingModel(String model) {
        return model.contains("flash-preview") || model.contains("pro-preview") || model.equals("gemini-3-pro");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildOpenAiVisionBody(String dataUri, ScanPlan plan, String model, int maxTokens) {
        Map<String, Object> imageContent = Map.of("type", "image_url", "image_url", Map.of("url", dataUri));
        Map<String, Object> textContent = Map.of("type", "text", "text", buildPrompt(plan));
        Map<String, Object> userMessage = Map.of("role", "user", "content", List.of(textContent, imageContent));
        Map<String, Object> systemMessage = Map.of("role", "system", "content", systemPrompt());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(systemMessage, userMessage));
        body.put("temperature", 0.4);
        body.put("top_p", 0.9);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        return body;
    }

    private Map<String, Object> buildGeminiBody(String base64Raw, String mimeType, ScanPlan plan,
                                                 String model, int maxTokens) {
        Map<String, Object> imagePart = Map.of("inline_data", Map.of("mime_type", mimeType, "data", base64Raw));
        Map<String, Object> textPart = Map.of("text", systemPrompt() + "\n\n" + buildPrompt(plan));

        Map<String, Object> generationConfig;
        if (isThinkingModel(model)) {
            // See GEMINI NOTE: combined thinking+output budget, so multiply
            // generously and force low thinking effort.
            generationConfig = Map.of(
                    "maxOutputTokens", Math.max(maxTokens * 8, 2048),
                    "thinkingConfig", Map.of("thinkingLevel", "low"));
        } else {
            generationConfig = Map.of("maxOutputTokens", maxTokens);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("parts", List.of(imagePart, textPart))));
        body.put("generationConfig", generationConfig);
        return body;
    }

    private Map<String, Object> buildCloudflareBody(String dataUri, String base64Raw, ScanPlan plan,
                                                      BodyShape shape, int maxTokens) {
        return switch (shape) {
            case CF_MOONDREAM -> {
                byte[] raw = Base64.getDecoder().decode(base64Raw);
                List<Integer> byteList = new ArrayList<>(raw.length);
                for (byte b : raw) byteList.add(b & 0xFF);
                yield Map.of(
                        "task", "query",
                        "image", byteList,
                        "question", buildPrompt(plan),
                        "max_tokens", maxTokens);
            }
            case CF_LLAMA_VISION -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("messages", List.of(
                        Map.of("role", "system", "content", "You are a helpful assistant."),
                        Map.of("role", "user", "content", buildPrompt(plan))));
                body.put("image", base64Raw);
                body.put("max_tokens", maxTokens);
                yield body;
            }
            default -> { // OPENAI_VISION shape, used for llama-4-scout / mistral-small / qwen3.8 on Workers AI
                Map<String, Object> imageContent = Map.of("type", "image_url", "image_url", Map.of("url", dataUri));
                Map<String, Object> textContent = Map.of("type", "text", "text", buildPrompt(plan));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("messages", List.of(Map.of("role", "user", "content", List.of(textContent, imageContent))));
                body.put("max_tokens", maxTokens);
                yield body;
            }
        };
    }

    // ------------------------------------------------------------------
    // HTTP dispatch
    // ------------------------------------------------------------------

    /**
     * Dispatches to the right HTTP call shape for the attempt's provider,
     * normalizing the response down to the raw model-output text string that
     * parseModelResponse() expects, regardless of which provider answered.
     */
    @SuppressWarnings("unchecked")
    private String callProvider(String dataUri, String mimeType, Attempt attempt, ScanPlan plan, AttemptResult ar) {
        String base64Raw = dataUri.substring(dataUri.indexOf(",") + 1);
        int maxTokens = attempt.provider().maxTokens();

        Object body = switch (attempt.provider().shape()) {
            case GEMINI_NATIVE -> buildGeminiBody(base64Raw, mimeType, plan, attempt.model(), maxTokens);
            case CF_LLAMA_VISION, CF_MOONDREAM -> buildCloudflareBody(dataUri, base64Raw, plan,
                    attempt.provider().shape(), maxTokens);
            default -> {
                if ("cloudflare".equals(attempt.provider().id())) {
                    yield buildCloudflareBody(dataUri, base64Raw, plan, BodyShape.OPENAI_VISION, maxTokens);
                }
                yield buildOpenAiVisionBody(dataUri, plan, attempt.model(), maxTokens);
            }
        };

        if (logRequestBody) {
            wire.info("[{}] request body: {}", attempt.label(), redactBody(body));
        }

        String url = buildUrlForAttempt(attempt);
        Map<String, String> headers = buildHeadersForAttempt(attempt);

        final long httpStart = System.currentTimeMillis();

        WebClient.RequestBodySpec spec = webClientBuilder.build().post().uri(url);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            spec = spec.header(h.getKey(), h.getValue());
        }

        Map<String, Object> result = (Map<String, Object>) spec
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), r ->
                        r.bodyToMono(String.class).defaultIfEmpty("<empty body>").map(respBody -> {
                            log.warn("[{}] HTTP {} after {}ms. Body: {}",
                                    attempt.label(), r.statusCode(),
                                    System.currentTimeMillis() - httpStart, truncate(respBody, 800));
                            return new ApiException("[" + attempt.label() + "] HTTP " + r.statusCode() +
                                    ": " + truncate(respBody, 500) + explainStatus(r.statusCode().value()),
                                    HttpStatus.BAD_GATEWAY);
                        }))
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(attemptTimeoutSeconds))
                .onErrorMap(ex -> !(ex instanceof ApiException),
                        ex -> new ApiException("[" + attempt.label() + "] " +
                                ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                                HttpStatus.BAD_GATEWAY))
                .block();

        long httpMs = System.currentTimeMillis() - httpStart;
        log.info("[{}] HTTP 200 in {}ms", attempt.label(), httpMs);

        if (result == null) {
            throw new ApiException("[" + attempt.label() + "] returned an empty response.", HttpStatus.BAD_GATEWAY);
        }

        return extractContentFromResponse(result, attempt, ar);
    }

    private String buildUrlForAttempt(Attempt attempt) {
        Provider p = attempt.provider();
        return switch (p.shape()) {
            case GEMINI_NATIVE -> p.baseUrl() + "/" + attempt.model() + ":generateContent";
            case CF_LLAMA_VISION, CF_MOONDREAM -> p.baseUrl() + "/" + attempt.model();
            default -> "cloudflare".equals(p.id())
                    ? p.baseUrl() + "/" + attempt.model()
                    : p.baseUrl() + "/chat/completions";
        };
    }

    private Map<String, String> buildHeadersForAttempt(Attempt attempt) {
        Provider p = attempt.provider();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (p.shape() == BodyShape.GEMINI_NATIVE) {
            headers.put("x-goog-api-key", p.apiKey());
        } else {
            headers.put("Authorization", "Bearer " + p.apiKey());
        }
        return headers;
    }

    /** Normalizes each provider's differently-shaped JSON response down to plain output text. */
    @SuppressWarnings("unchecked")
    private String extractContentFromResponse(Map<String, Object> result, Attempt attempt, AttemptResult ar) {
        Object errorNode = result.get("error");
        if (errorNode != null) {
            log.warn("[{}] HTTP 200 but body contains an error object: {}",
                    attempt.label(), truncate(String.valueOf(errorNode), 600));
            throw new ApiException("[" + attempt.label() + "] provider error: " +
                    truncate(String.valueOf(errorNode), 400), HttpStatus.BAD_GATEWAY);
        }

        if (attempt.provider().shape() == BodyShape.GEMINI_NATIVE) {
            List<Object> candidates = (List<Object>) result.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new ApiException("[" + attempt.label() + "] returned no candidates.", HttpStatus.BAD_GATEWAY);
            }
            Map<String, Object> candidate = (Map<String, Object>) candidates.get(0);
            String finishReason = String.valueOf(candidate.get("finishReason"));
            ar.finishReason = finishReason;
            Map<String, Object> contentObj = (Map<String, Object>) candidate.get("content");
            List<Object> parts = contentObj != null ? (List<Object>) contentObj.get("parts") : null;
            String text = parts == null ? null : parts.stream()
                    .map(p -> ((Map<String, Object>) p).get("text"))
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .collect(Collectors.joining());

            Object usage = result.get("usageMetadata");
            if (usage instanceof Map<?, ?> u) {
                ar.promptTokens = asInt(u.get("promptTokenCount"));
                ar.completionTokens = asInt(u.get("candidatesTokenCount"));
            }

            if (text == null || text.isBlank()) {
                throw new ApiException("[" + attempt.label() + "] empty response (finishReason=" +
                        finishReason + ")", HttpStatus.BAD_GATEWAY);
            }
            return text;
        }

        // Cloudflare's non-chat shapes (llama-vision, moondream) return a
        // "result" object rather than OpenAI-style "choices".
        if ("cloudflare".equals(attempt.provider().id()) &&
                (attempt.provider().shape() == BodyShape.CF_LLAMA_VISION ||
                 attempt.provider().shape() == BodyShape.CF_MOONDREAM)) {
            Object resultObj = result.get("result");
            if (resultObj instanceof Map<?, ?> r) {
                Object text = r.get("response") != null ? r.get("response")
                        : r.get("description") != null ? r.get("description")
                        : r.get("answer");
                if (text != null && !String.valueOf(text).isBlank()) {
                    return String.valueOf(text);
                }
            }
            throw new ApiException("[" + attempt.label() + "] empty or unrecognized Cloudflare response shape.",
                    HttpStatus.BAD_GATEWAY);
        }

        // Everything else (OpenRouter, Cloudflare's OpenAI-compatible models)
        // is standard OpenAI chat-completions shape.
        List<Object> choices = (List<Object>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            log.warn("[{}] no choices in response. Raw: {}", attempt.label(), truncate(String.valueOf(result), 800));
            throw new ApiException("[" + attempt.label() + "] returned no choices.", HttpStatus.BAD_GATEWAY);
        }

        Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);
        Object finish = firstChoice.get("finish_reason");
        if (finish != null) {
            ar.finishReason = String.valueOf(finish);
            if ("length".equals(ar.finishReason)) {
                log.warn("[{}] finish_reason=length - output was TRUNCATED by max_tokens ({}). " +
                        "Raise ai.<provider>.max-tokens or lower the pick cap.",
                        attempt.label(), attempt.provider().maxTokens());
            }
        }

        Object usageObj = result.get("usage");
        if (usageObj instanceof Map<?, ?> usage) {
            ar.promptTokens = asInt(usage.get("prompt_tokens"));
            ar.completionTokens = asInt(usage.get("completion_tokens"));
        }

        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        Object content = message != null ? message.get("content") : null;

        if (content == null || content.toString().isBlank()) {
            throw new ApiException("[" + attempt.label() + "] returned an empty message.", HttpStatus.BAD_GATEWAY);
        }

        String text = content.toString();
        log.info("[{}] content: {} chars", attempt.label(), text.length());
        if (logFullResponse) {
            wire.info("[{}] full content:\n{}", attempt.label(), text);
        } else {
            log.debug("[{}] preview: {}", attempt.label(), truncate(text.replace('\n', ' '), previewChars));
        }
        return text;
    }

    /** Turns common HTTP codes into an actionable hint appended to the error. */
    private String explainStatus(int status) {
        return switch (status) {
            case 400 -> " | Hint: malformed request or unsupported/retired model id - " +
                    "double check ai.<provider>.models against the live catalog.";
            case 401 -> " | Hint: API key invalid, wrong header, or missing the right scope. " +
                    "Gemini uses x-goog-api-key, not Authorization: Bearer.";
            case 402 -> " | Hint: billing/credits required - this model id is no longer on the free tier, " +
                    "or this account has depleted its included credits. The next account in this " +
                    "provider's rotation (ai.<provider>.api-keys) will be tried automatically.";
            case 403 -> " | Hint: Gemini - key not enabled for the Generative Language API. " +
                    "Cloudflare Llama 3.2 Vision - requires a one-time 'agree' prompt sent to the model " +
                    "before real use (Meta license gate); see the class javadoc.";
            case 404 -> " | Hint: model id not found. On Cloudflare and NVIDIA-style catalogs this often " +
                    "means the model was quietly retired - verify against the provider's live catalog.";
            case 410 -> " | Hint: model permanently retired ('reached end of life'). This is common enough " +
                    "on fast-moving free catalogs that model ids should be re-verified periodically - " +
                    "see the MODEL CATALOG note in this class's javadoc.";
            case 413 -> " | Hint: payload too large - lower ai.image.max-edge-px.";
            case 422 -> " | Hint: model likely does not accept image input (not a VLM). Double-check the " +
                    "model's modality tag on the provider's site.";
            case 429 -> " | Hint: rate or token limited. The next account in this provider's rotation " +
                    "(ai.<provider>.api-keys) will be tried automatically; if all accounts on this " +
                    "provider are exhausted, the chain falls through to the next provider.";
            case 503 -> " | Hint: model cold-starting or provider unavailable; retry shortly.";
            default -> "";
        };
    }

    // ------------------------------------------------------------------
    // Image preparation
    // ------------------------------------------------------------------

    private String[] prepareImage(String imageBase64, String imageMediaType) {
        long started = System.currentTimeMillis();
        try {
            byte[] raw = Base64.getDecoder().decode(imageBase64);
            log.info("Image in: {} KB raw, {} KB base64, type {}",
                    raw.length / 1024, imageBase64.length() / 1024, imageMediaType);

            BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
            if (src == null) {
                log.warn("Could not decode image (unsupported format?), sending original bytes unchanged");
                return new String[]{imageBase64, imageMediaType};
            }
            log.debug("Decoded image: {}x{} px, type {}", src.getWidth(), src.getHeight(), src.getType());

            int edge = maxEdgePx;
            float quality = jpegQuality;

            for (int i = 0; i < 4; i++) {
                byte[] encoded = encodeJpeg(scale(src, edge), quality);
                String b64 = Base64.getEncoder().encodeToString(encoded);
                log.debug("Encode pass {}: edge={}px quality={} -> {} KB base64",
                        i + 1, edge, quality, b64.length() / 1024);

                if (b64.length() <= maxBase64Bytes) {
                    log.info("Image out: edge {}px, quality {}, {} KB base64 ({}ms)",
                            edge, quality, b64.length() / 1024, System.currentTimeMillis() - started);
                    return new String[]{b64, "image/jpeg"};
                }
                edge = (int) (edge * 0.75);
                quality = Math.max(0.4f, quality - 0.1f);
            }

            byte[] encoded = encodeJpeg(scale(src, edge), 0.4f);
            String b64 = Base64.getEncoder().encodeToString(encoded);
            log.warn("Image still {} KB base64 after max compression (limit {} KB); sending anyway",
                    b64.length() / 1024, maxBase64Bytes / 1024);
            return new String[]{b64, "image/jpeg"};

        } catch (Exception ex) {
            log.warn("Image preparation failed, falling back to original bytes", ex);
            return new String[]{imageBase64, imageMediaType};
        }
    }

    private BufferedImage scale(BufferedImage src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (Math.max(w, h) <= maxEdge) {
            return toRgb(src);
        }
        double factor = (double) maxEdge / Math.max(w, h);
        int nw = Math.max(1, (int) Math.round(w * factor));
        int nh = Math.max(1, (int) Math.round(h * factor));

        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    /** JPEG cannot carry alpha; flatten to RGB first or encoding throws. */
    private BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private byte[] encodeJpeg(BufferedImage img, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer available in this JRE");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(img, null, null), param);
            ios.flush();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private ScanAnalysis parseModelResponse(String content, ScanPlan plan, String label) {
        ScanAnalysis analysis = new ScanAnalysis();
        String jsonText = extractJson(content);

        if (jsonText.length() != content.trim().length()) {
            log.debug("[{}] stripped {} chars of non-JSON wrapper from the response",
                    label, content.trim().length() - jsonText.length());
        }

        try {
            JsonNode root = objectMapper.readTree(jsonText);
            analysis.totalPicksDetected = root.path("totalPicksDetected").asInt(
                    root.path("totalMatches").asInt(0));

            JsonNode picksNode = root.path("picks");
            if (!picksNode.isArray()) {
                picksNode = root.path("predictions");
                if (picksNode.isArray()) {
                    log.debug("[{}] model used 'predictions' key instead of 'picks'; handled", label);
                }
            }

            int cap = plan.isFullCoverage() ? Integer.MAX_VALUE : plan.getMaxPicks();

            if (!picksNode.isArray()) {
                log.warn("[{}] parsed JSON has no picks/predictions array. Keys present: {}",
                        label, fieldNames(root));
            } else {
                int available = picksNode.size();
                int count = 0;
                for (JsonNode pickNode : picksNode) {
                    if (count >= cap) {
                        log.info("[{}] plan cap reached: kept {} of {} pick(s) returned",
                                label, cap, available);
                        break;
                    }
                    PickPrediction pick = new PickPrediction();
                    pick.setSectionIndex(pickNode.path("sectionIndex").asInt(
                            pickNode.path("matchNumber").asInt(count + 1)));
                    pick.setMatchLabel(pickNode.path("matchLabel").asText(""));
                    pick.setOriginalPick(pickNode.path("originalPick").asText(""));
                    pick.setPrediction(pickNode.path("prediction").asText(""));
                    pick.setConfidence(pickNode.path("confidence").asText(""));
                    pick.setAnalysis(pickNode.path("analysis").asText(
                            pickNode.path("reason").asText("")));
                    analysis.predictions.add(pick);
                    count++;
                }
                log.info("[{}] parsed {} pick(s) from {} returned, {} detected on slip",
                        label, count, available, analysis.totalPicksDetected);
            }

            if (analysis.totalPicksDetected == 0) {
                analysis.totalPicksDetected = analysis.predictions.size();
            }

        } catch (Exception ex) {
            log.warn("[{}] JSON parse FAILED: {}. First 500 chars of payload: {}",
                    label, ex.getMessage(), truncate(jsonText, 500));
            analysis.rawModelOutput = content;
        }

        return analysis;
    }

    /** Strips ```json fences etc, in case the model doesn't follow instructions perfectly. */
    private String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int fenceEnd = trimmed.lastIndexOf("```");
            if (fenceEnd != -1) {
                trimmed = trimmed.substring(0, fenceEnd);
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static List<String> splitCsv(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        return Stream.of(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Never print a key in full. Shows only enough to identify which key is loaded. */
    private static String mask(String key) {
        if (isBlank(key)) {
            return "<none>";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 3) +
                " (len " + key.length() + ")";
    }

    /** Replaces the base64 data URI / byte array with a placeholder so log files stay readable. */
    private String redactBody(Object body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            json = json.replaceAll("data:image/[a-zA-Z]+;base64,[A-Za-z0-9+/=]+",
                    "data:image/...;base64,<REDACTED>");
            json = json.replaceAll("\"data\"\\s*:\\s*\"[A-Za-z0-9+/=]{40,}\"", "\"data\":\"<REDACTED>\"");
            return json;
        } catch (Exception ex) {
            return "<body could not be serialized: " + ex.getMessage() + ">";
        }
    }

    private static String fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    private static Integer asInt(Object o) {
        return (o instanceof Number n) ? n.intValue() : null;
    }

    private static String nz(Integer i) {
        return i == null ? "-" : String.valueOf(i);
    }

    /** Walks the cause chain so wrapped exceptions still surface something useful. */
    private static String rootMessage(Throwable ex) {
        Throwable cur = ex;
        String msg = ex.getMessage();
        int guard = 0;
        while (cur.getCause() != null && cur.getCause() != cur && guard++ < 10) {
            cur = cur.getCause();
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                msg = cur.getMessage();
            }
        }
        return msg == null ? ex.toString() : msg;
    }
}
