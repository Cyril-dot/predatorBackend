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
 * Calls OpenAI-compatible chat completions endpoints to analyze a betting-slip
 * image and produce per-pick predictions.
 *
 * Despite the class name (kept so existing injection points don't change), this
 * is provider-neutral. It walks a chain of providers, each with its own model
 * list. The default chain prioritizes general multimodal models and can fall
 * back to a specialist vision model only at the end:
 *
 *   groq        https://api.groq.com/openai/v1                    <- PRIMARY: fastest inference, but only ONE free vision model right now
 *   openrouter  https://openrouter.ai/api/v1                       <- secondary free multimodal models
 *   nvidia      https://integrate.api.nvidia.com/v1                <- tertiary multimodal models (build.nvidia.com)
 *   huggingface https://router.huggingface.co/v1                   <- optional last-resort VLM
 *
 * ============================================================================
 * MODEL CATALOG LAST VERIFIED: 2026-09-05. This is the single fastest-moving
 * part of this class. Free-tier vision model rosters on every provider below
 * change on the order of weeks, not months - model ids get deprecated, preview
 * models get promoted or killed, and "free" models get moved behind a paywall
 * with zero notice. ALWAYS check the live catalog before trusting this list:
 *   - Groq:        console.groq.com/docs/models  (also console.groq.com/docs/deprecations)
 *   - OpenRouter:  openrouter.ai/collections/free-models (filter for vision/image input)
 *   - NVIDIA:      build.nvidia.com/search?label=VLM
 *   - HuggingFace: huggingface.co/models?pipeline_tag=image-text-to-text&sort=trending
 *   - Gemini:      ai.google.dev/gemini-api/docs/models (check the changelog for shutdown dates)
 * ============================================================================
 *
 * GROQ NOTE: Groq deprecated its llama-3.2-*-vision-* models AND
 * meta-llama/llama-4-scout-17b-16e-instruct in 2026 (see the deprecations page
 * above for exact dates). As of this writing, Groq's only vision-capable chat
 * model is qwen/qwen3.6-27b, and it is served as a PREVIEW model - Groq's own
 * docs say preview models "are intended for evaluation, not production" and
 * "may be discontinued at short notice." Treat Groq as a speed-optimized
 * opportunistic first attempt, not a guaranteed vision provider; the chain
 * falling through to OpenRouter/NVIDIA is expected, not a bug. Free tier: 30
 * RPM, no credit card required. Get a key at https://console.groq.com/keys.
 * Multiple keys are supported via ai.groq.api-keys (comma-separated).
 *
 * OPENROUTER NOTE: model ids below are pulled from OpenRouter's official
 * "Free AI Models" collection (openrouter.ai/collections/free-models) as of
 * 2026-09-05, filtered to entries whose description explicitly mentions image
 * input. minimax/minimax-m3:free and the nvidia/thinkingmachines entries are
 * genuinely multimodal (text+image, some also video/audio); a lot of the OTHER
 * free models on that page (Nemotron Ultra, the Laguna/Ling/GLM family, LFM2.5)
 * are TEXT-ONLY despite being strong models generally - don't add them here
 * without checking the modality tag first, they'll 422 on an image payload.
 *
 * NVIDIA NOTE (build.nvidia.com): nemotron-nano-12b-v2-vl and
 * nemotron-3-nano-omni-30b-a3b-reasoning are NVIDIA's current general-purpose
 * VLMs (confirmed on build.nvidia.com/search?label=VLM). Also listed:
 * llama-3.1-nemotron-nano-vl-8b-v1, a smaller doc/OCR-oriented VLM that's a
 * reasonable last-in-list fallback for a betting-slip (i.e. document-like)
 * image. qwen3.5-397b-a17b is a large Qwen VLM also hosted here if you want a
 * bigger fallback model. NVIDIA's build platform issues free API credits on
 * signup (historically ~1000, expandable on request) rather than a flat
 * "free forever" tier for every model - verify current credit/rate-limit
 * terms for whichever key you provision.
 *
 * GEMINI NOTE: Google's Gemini API exposes an OpenAI-compatible endpoint at
 * /v1beta/openai/chat/completions. gemini-2.5-flash and gemini-2.5-flash-lite
 * are still live and free-tier eligible but are SCHEDULED TO SHUT DOWN ON
 * 2026-10-16 - after that date, drop them from ai.gemini.models (they'll start
 * 404ing) and rely on gemini-3.5-flash (GA since 2026-05-19, Google's current
 * flagship Flash model) and gemini-3.1-flash-lite (stable since 2026-05-07) instead.
 * gemini-3-flash-preview is also usable today but is a preview id with no
 * committed shutdown date, so it can move without warning. Get a key at
 * https://aistudio.google.com/apikey and set ai.gemini.api-key (or
 * ai.gemini.api-keys for multiple).
 *
 * GEMINI MULTI-KEY NOTE: you can supply MULTIPLE Gemini API keys via
 * ai.gemini.api-keys=key1,key2,key3 (comma-separated). Each key is expanded
 * into its own Provider in the attempt chain, in the order given, each trying
 * every model in ai.gemini.models before moving to the next key. This means a
 * key that is rate-limited (429) or out of free-tier credits (402) fails over
 * to the next Gemini key BEFORE the chain ever falls through to the next
 * provider. ai.gemini.api-key (singular) still works as a one-key shorthand
 * and is used only if ai.gemini.api-keys is not set. The same ai.<id>.api-keys
 * pattern works for any provider, not just gemini.
 *
 * CEREBRAS NOTE - REMOVED FROM DEFAULT CHAIN: as of this writing there is no
 * confirmed vision-capable model on Cerebras's free tier (their listed free
 * models are Llama 3.3 70B, Llama 4 Scout, and DeepSeek R1, which are text-only
 * chat models). The previous default of "gemma-4-31b" could not be verified
 * against Cerebras's current catalog, so it has been removed rather than ship
 * an id that may 404. The provider plumbing (ai.cerebras.*) is left in place -
 * if Cerebras ships a confirmed vision model, add it to ai.providers and set
 * ai.cerebras.models yourself once you've verified the id at
 * https://inference-docs.cerebras.ai/models.
 *
 * Providers whose API key(s) are blank are SKIPPED, so you can deploy with only
 * one of the keys set (though having multiple is recommended so providers can
 * cover each other's outages/rate limits).
 *
 * LOGGING: every scan gets a short trace id (MDC key "scanId") that prefixes all
 * log lines for that request, so concurrent scans stay untangled in the log file.
 * Each attempt logs the outbound request summary, HTTP status, latency, token
 * usage, finish reason, and a preview of the returned content. A summary table
 * of all attempts is printed at the end whether the scan succeeded or failed.
 * API keys are always masked; the base64 image is never logged.
 */
@Service
public class NvidiaAiService {

    private static final Logger log = LoggerFactory.getLogger(NvidiaAiService.class);
    /** Separate logger so raw request/response payloads can be toggled independently. */
    private static final Logger wire = LoggerFactory.getLogger(NvidiaAiService.class.getName() + ".wire");

    private static final String MDC_SCAN_ID = "scanId";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebClient.Builder webClientBuilder;
    private final Environment env;

    // ---- provider chain -------------------------------------------------

    /**
     * Ordered, comma-separated provider ids to try.
     * Groq is first because it is the fastest (LPU), even though it currently
     * has only one (preview) vision model - a quick win when it works, with
     * OpenRouter/NVIDIA/HuggingFace as the real depth behind it.
     */
    @Value("${ai.providers:groq,openrouter,nvidia,huggingface}")
    private String providersRaw;

    /**
     * Per-attempt timeout. A vision model producing 2-4k tokens of JSON with
     * stream=false routinely needs 20-60s, so a short default guarantees that
     * every model in the chain "times out".
     */
    @Value("${ai.attempt-timeout-seconds:60}")
    private long attemptTimeoutSeconds;

    // ---- image prep -----------------------------------------------------

    @Value("${ai.image.max-edge-px:1024}")
    private int maxEdgePx;

    @Value("${ai.image.max-base64-bytes:180000}")
    private int maxBase64Bytes;

    @Value("${ai.image.jpeg-quality:0.75}")
    private float jpegQuality;

    // ---- logging switches ----------------------------------------------

    /** Log the full model output text on every attempt (not just a preview). */
    @Value("${ai.log.full-response:false}")
    private boolean logFullResponse;

    /** Log the outbound JSON body (image data URI is always redacted). */
    @Value("${ai.log.request-body:false}")
    private boolean logRequestBody;

    /** Characters of model output shown in the preview line. */
    @Value("${ai.log.preview-chars:400}")
    private int previewChars;

    public NvidiaAiService(WebClient.Builder webClientBuilder, Environment env) {
        this.webClientBuilder = webClientBuilder;
        this.env = env;
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    public record Provider(String id, String baseUrl, String apiKey, List<String> models) {
    }

    private record Attempt(Provider provider, String model) {
        String label() {
            return provider.id() + "/" + model;
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
                log.error("No usable provider. Configured chain=[{}] but none had an API key.", providersRaw);
                throw new ApiException(
                        "AI scanning is not configured: no provider in [" + providersRaw +
                                "] has an API key set. Configure the provider-specific " +
                                "ai.<provider>.api-key or ai.<provider>.api-keys property, starting with " +
                                "GROQ_API_KEY for Groq (fastest free tier, get one at console.groq.com/keys).",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }

            log.info("Attempt chain ({} attempts, {}s timeout each):", attempts.size(), attemptTimeoutSeconds);
            for (int i = 0; i < attempts.size(); i++) {
                Attempt a = attempts.get(i);
                log.info("  [{}/{}] {} -> {} (key {})",
                        i + 1, attempts.size(), a.label(), a.provider().baseUrl(), mask(a.provider().apiKey()));
            }

            String[] prepared = prepareImage(imageBase64, imageMediaType);
            String dataUri = "data:" + prepared[1] + ";base64," + prepared[0];

            Map<String, Object> baseBody = buildRequestBody(dataUri, plan);
            List<AttemptResult> results = new ArrayList<>();

            for (int i = 0; i < attempts.size(); i++) {
                Attempt attempt = attempts.get(i);
                AttemptResult ar = new AttemptResult();
                ar.label = attempt.label();
                long started = System.currentTimeMillis();

                try {
                    Map<String, Object> body = new LinkedHashMap<>(baseBody);
                    body.put("model", attempt.model());

                    log.info(">>> ATTEMPT {}/{} [{}] POST {}/chat/completions",
                            i + 1, attempts.size(), attempt.label(), attempt.provider().baseUrl());

                    if (logRequestBody) {
                        wire.info("[{}] request body: {}", attempt.label(), redactBody(body));
                    }

                    String content = callChatCompletions(body, attempt, ar);

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
                    "AI scanning failed. All " + attempts.size() + " attempt(s) failed:\n" + failureList,
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
     * Builds the full ordered list of attempts.
     *
     * For each provider id in ai.providers (default "groq,openrouter,nvidia,huggingface"):
     *   1. Resolve its base URL and model list (falling back to the built-in
     *      defaults in defaultBaseUrl()/defaultModels() if not configured).
     *   2. Resolve its API key(s):
     *        - ai.<id>.api-keys  (comma-separated, preferred - supports N keys)
     *        - ai.<id>.api-key   (single key, used only if api-keys is blank)
     *   3. Expand into one Provider PER KEY, each carrying the SAME model list.
     *      Provider ids become "<id>-key1", "<id>-key2", ... when there is more
     *      than one key, so the attempt-chain log and the summary table make it
     *      obvious which key answered (or failed) without ever printing the key
     *      itself - see mask().
     *   4. Flatten every (key x model) pair into the attempt chain, in order:
     *      all models for key 1, then all models for key 2, etc. A key that is
     *      rate-limited or out of credits therefore fails over to the NEXT KEY
     *      of the same provider before the chain moves on to the next provider.
     */
    private List<Attempt> buildAttemptChain() {
        List<Attempt> chain = new ArrayList<>();

        for (String id : splitCsv(providersRaw)) {
            String baseUrl = env.getProperty("ai." + id + ".base-url", defaultBaseUrl(id));
            String models = env.getProperty("ai." + id + ".models", "");

            if (isBlank(models)) {
                models = defaultModels(id);
            }

            if (isBlank(baseUrl)) {
                log.warn("Provider [{}] SKIPPED: no base-url configured and no built-in default", id);
                continue;
            }

            List<String> modelList = splitCsv(models);
            if (modelList.isEmpty()) {
                log.warn("Provider [{}] SKIPPED: no models configured", id);
                continue;
            }

            List<String> apiKeys = resolveApiKeys(id);
            if (apiKeys.isEmpty()) {
                log.info("Provider [{}] SKIPPED: no API key(s) set (ai.{}.api-key or ai.{}.api-keys)",
                        id, id, id);
                continue;
            }

            log.debug("Provider [{}] ENABLED: {} with {} key(s), {} model(s) {}",
                    id, baseUrl, apiKeys.size(), modelList.size(), modelList);

            boolean multiKey = apiKeys.size() > 1;
            for (int k = 0; k < apiKeys.size(); k++) {
                String key = apiKeys.get(k);
                String providerId = multiKey ? id + "-key" + (k + 1) : id;
                Provider provider = new Provider(providerId, stripTrailingSlash(baseUrl), key, modelList);
                for (String m : modelList) {
                    chain.add(new Attempt(provider, m));
                }
            }
        }

        return chain;
    }

    /**
     * Resolves the ordered list of API keys for a provider id.
     * Prefers ai.<id>.api-keys (comma-separated list, dedup'd, blanks dropped).
     * Falls back to the single ai.<id>.api-key property when api-keys is unset.
     */
    private List<String> resolveApiKeys(String id) {
        List<String> apiKeys = splitCsv(env.getProperty("ai." + id + ".api-keys", ""));
        if (!apiKeys.isEmpty()) {
            return apiKeys;
        }
        String singleKey = env.getProperty("ai." + id + ".api-key", "");
        if (!isBlank(singleKey)) {
            return List.of(singleKey);
        }
        return List.of();
    }

    private String defaultBaseUrl(String id) {
        return switch (id) {
            case "groq"        -> "https://api.groq.com/openai/v1";
            case "openrouter"  -> "https://openrouter.ai/api/v1";
            case "nvidia"      -> "https://integrate.api.nvidia.com/v1";
            case "huggingface" -> "https://router.huggingface.co/v1";
            case "gemini"      -> "https://generativelanguage.googleapis.com/v1beta/openai";
            case "cerebras"    -> "https://api.cerebras.ai/v1";
            default            -> null;
        };
    }

    /**
     * Multimodal-capable defaults, last verified 2026-09-05 (see the class
     * javadoc for links to check each provider's live catalog before trusting
     * this - free vision rosters change fast and a retired id returns a 404
     * that looks like an outage).
     *
     * GROQ: qwen/qwen3.6-27b is currently the ONLY vision-capable chat model
     * Groq serves; it is a preview model, not guaranteed stable. The old
     * llama-3.2-*-vision-* and llama-4-scout ids are DEPRECATED - do not use.
     *
     * OPENROUTER: every id below is explicitly multimodal (image input) per
     * OpenRouter's free-models collection. minimax-m3 also takes video;
     * nemotron-3-nano-omni also takes video+audio; the two "inkling" models
     * are Thinking Machines' native image+audio multimodal models at two
     * sizes (41B and 12B active params) so the smaller one is a good final
     * fallback if the bigger ones are rate-limited.
     *
     * NVIDIA (build.nvidia.com): nemotron-nano-12b-v2-vl and
     * nemotron-3-nano-omni-30b-a3b-reasoning are NVIDIA's general-purpose
     * VLMs; qwen3.5-397b-a17b is a larger third-party VLM also hosted here;
     * llama-3.1-nemotron-nano-vl-8b-v1 is smaller and doc/OCR-leaning, which
     * suits a printed betting slip well as a last-resort fallback.
     *
     * HUGGINGFACE: Qwen3-VL-8B-Instruct is the current Qwen vision-language
     * model on Hugging Face's router; this is the OPTIONAL last-resort model
     * (only used if ai.huggingface.api-key / HF token is set).
     *
     * GEMINI (optional, kept for backwards compatibility): gemini-3.5-flash
     * is Google's current flagship free-tier-eligible Flash model (GA since
     * 2026-05-19); gemini-3.1-flash-lite is the stable lighter option;
     * gemini-3-flash-preview is usable but a preview id with no committed
     * shutdown date. gemini-2.5-flash / gemini-2.5-flash-lite are kept as
     * legacy fallbacks but are SCHEDULED TO SHUT DOWN 2026-10-16 - remove
     * them from ai.gemini.models once that date passes.
     *
     * CEREBRAS: no default models - see the CEREBRAS NOTE in the class
     * javadoc for why this provider currently has nothing to offer here.
     */
    private String defaultModels(String id) {
        return switch (id) {
            // Groq: fastest inference (LPU). Only one vision-capable model
            // right now, and it's a preview - expect this attempt to
            // sometimes fail over to the next provider, that's by design.
            case "groq" -> "qwen/qwen3.6-27b";
            case "openrouter" -> String.join(",",
                    "minimax/minimax-m3:free",
                    "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
                    "thinkingmachines/inkling:free",
                    "thinkingmachines/inkling-small:free");
            case "nvidia" -> String.join(",",
                    "nvidia/nemotron-nano-12b-v2-vl",
                    "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
                    "qwen/qwen3.5-397b-a17b",
                    "nvidia/llama-3.1-nemotron-nano-vl-8b-v1");
            // Optional last-resort HF vision model; only used if HF_TOKEN is set.
            case "huggingface" -> "Qwen/Qwen3-VL-8B-Instruct";
            // Optional provider retained for backwards compatibility.
            case "gemini" -> String.join(",",
                    "gemini-3.5-flash",
                    "gemini-3.1-flash-lite",
                    "gemini-3-flash-preview",
                    "gemini-2.5-flash",
                    "gemini-2.5-flash-lite");
            // No confirmed vision-capable model on Cerebras's free tier as of
            // 2026-09-05 - see the CEREBRAS NOTE above. Left empty on purpose;
            // buildAttemptChain() will SKIP this provider with a log line
            // rather than send a request to a guessed model id.
            case "cerebras" -> "";
            default -> "";
        };
    }

    // ------------------------------------------------------------------
    // Request body
    // ------------------------------------------------------------------

    private Map<String, Object> buildRequestBody(String dataUri, ScanPlan plan) {

        Map<String, Object> imageContent = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUri));

        String prompt = buildPrompt(plan);

        Map<String, Object> textContent = Map.of(
                "type", "text",
                "text", prompt);

        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", List.of(textContent, imageContent));

        // ONE schema only. The previous version had a system prompt demanding
        // {"predictions":[{"matchNumber",...}]} and a user prompt demanding
        // {"picks":[{"sectionIndex",...}]}, while the parser only read the second -
        // so a model that obeyed the system prompt produced zero picks.
        Map<String, Object> systemMessage = Map.of(
                "role", "system",
                "content",
                """
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
                """);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(systemMessage, userMessage));
        body.put("temperature", 0.4);
        body.put("top_p", 0.9);
        body.put("max_tokens", 4096);
        body.put("stream", false);

        log.debug("Request built: prompt {} chars, image data-uri {} chars, max_tokens 4096, temp 0.4",
                prompt.length(), dataUri.length());

        return body;
    }

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

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /**
     * Single attempt against one provider+model. Short per-attempt timeout, and
     * crucially NO retryWhen(Retry.max(0)) - that operator wraps the real error in
     * RetryExhaustedException, which then fails the "instanceof ApiException" check
     * in onErrorMap and produces the useless "Retries exhausted: 0/0" message that
     * hid the actual cause. Omitting the operator entirely is how you say
     * "do not retry"; the caller handles failover.
     */
    @SuppressWarnings("unchecked")
    private String callChatCompletions(Map<String, Object> body, Attempt attempt, AttemptResult ar) {

        WebClient.RequestBodySpec spec = webClientBuilder.build()
                .post()
                .uri(attempt.provider().baseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + attempt.provider().apiKey())
                .header("Content-Type", "application/json");

        final long httpStart = System.currentTimeMillis();

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
            throw new ApiException("[" + attempt.label() + "] returned an empty response.",
                    HttpStatus.BAD_GATEWAY);
        }

        // Some providers return {"error": {...}} with HTTP 200.
        Object errorNode = result.get("error");
        if (errorNode != null) {
            log.warn("[{}] HTTP 200 but body contains an error object: {}",
                    attempt.label(), truncate(String.valueOf(errorNode), 600));
            throw new ApiException("[" + attempt.label() + "] provider error: " +
                    truncate(String.valueOf(errorNode), 400), HttpStatus.BAD_GATEWAY);
        }

        // Token usage, useful for cost tracking on paid endpoints.
        Object usageObj = result.get("usage");
        if (usageObj instanceof Map<?, ?> usage) {
            ar.promptTokens = asInt(usage.get("prompt_tokens"));
            ar.completionTokens = asInt(usage.get("completion_tokens"));
            log.info("[{}] usage: prompt={} completion={} total={}",
                    attempt.label(), nz(ar.promptTokens), nz(ar.completionTokens),
                    nz(asInt(usage.get("total_tokens"))));
            // Some providers (e.g. Gemini) report image tokens separately -
            // useful to see how much of the prompt budget the image consumed.
            Object imageTokens = usage.get("image_tokens");
            if (imageTokens != null) {
                log.info("[{}] image_tokens={}", attempt.label(), imageTokens);
            }
        }
        if (result.get("provider") != null) {
            log.info("[{}] routed to upstream provider: {}", attempt.label(), result.get("provider"));
        }

        List<Object> choices = (List<Object>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            log.warn("[{}] no choices in response. Raw: {}", attempt.label(),
                    truncate(String.valueOf(result), 800));
            throw new ApiException("[" + attempt.label() + "] returned no choices.",
                    HttpStatus.BAD_GATEWAY);
        }

        Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);

        Object finish = firstChoice.get("finish_reason");
        if (finish != null) {
            ar.finishReason = String.valueOf(finish);
            if ("length".equals(ar.finishReason)) {
                log.warn("[{}] finish_reason=length - output was TRUNCATED by max_tokens, so the JSON " +
                        "is likely incomplete. Raise max_tokens or lower the pick cap.", attempt.label());
            } else {
                log.debug("[{}] finish_reason={}", attempt.label(), ar.finishReason);
            }
        }

        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        Object content = message != null ? message.get("content") : null;

        if (content == null || content.toString().isBlank()) {
            log.warn("[{}] empty message content. Choice: {}", attempt.label(),
                    truncate(String.valueOf(firstChoice), 600));
            throw new ApiException("[" + attempt.label() + "] returned an empty message.",
                    HttpStatus.BAD_GATEWAY);
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
            case 400 -> " | Hint: Returns 400 for a malformed request or an unsupported/retired model id - " +
                    "double check ai.<provider>.models against the live catalog. " +
                    "Groq: verify model ids at console.groq.com/docs/models (llama-3.2-vision and " +
                    "llama-4-scout are RETIRED there - use qwen/qwen3.6-27b).";
            case 401 -> " | Hint: API key invalid, wrong header, or missing the right scope. " +
                    "Groq keys are generated at console.groq.com/keys.";
            case 402 -> " | Hint: billing/credits required - this model id is no longer on the free tier, " +
                    "or this key has depleted its included credits. If you configured multiple keys via " +
                    "ai.<provider>.api-keys, the next key in the list will be tried automatically.";
            case 403 -> " | Hint: Gemini - key not enabled for the Generative Language API, or a Pro " +
                    "model was requested on a free-tier key. NVIDIA - model may require additional " +
                    "credits/approval on build.nvidia.com. Groq - account may need verification.";
            case 404 -> " | Hint: model id not found or retired. Verify it in the provider's catalog - " +
                    "see the MODEL CATALOG note in this class's javadoc for the right URL per provider.";
            case 413 -> " | Hint: payload too large - lower ai.image.max-edge-px.";
            case 422 -> " | Hint: model likely does not accept image input (not a VLM). Double-check the " +
                    "model's modality tag on the provider's site - several free text-only models (e.g. " +
                    "OpenRouter's Nemotron Ultra, Laguna, Ling, GLM 5.2 entries) will 422 on an image payload.";
            case 429 -> " | Hint: rate limited. Groq free tier is capped at 30 RPM; NVIDIA and Gemini free " +
                    "tiers are similarly limited per minute/day. If multiple keys are configured via " +
                    "ai.<provider>.api-keys, the next key will be tried automatically; otherwise the next " +
                    "provider in the chain takes over.";
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
            // Log it. The old code swallowed this silently and returned a
            // valid-looking empty result, making parse failures indistinguishable
            // from success upstream.
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

    /** Replaces the base64 data URI with a placeholder so log files stay readable. */
    private String redactBody(Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            return json.replaceAll("data:image/[a-zA-Z]+;base64,[A-Za-z0-9+/=]+",
                    "data:image/...;base64,<REDACTED>");
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
