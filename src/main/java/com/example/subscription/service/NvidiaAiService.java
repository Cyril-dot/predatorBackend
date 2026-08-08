package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.PickPrediction;
import com.example.subscription.model.ScanPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Calls an NVIDIA-hosted, OpenAI-compatible chat completions endpoint
 * (https://integrate.api.nvidia.com/v1/chat/completions) to analyze a
 * betting-slip image and produce per-pick predictions.
 *
 * IMPORTANT: the model id below (nvidia.model in application.properties) must
 * point to a vision-capable ("VLM") NVIDIA NIM model for image input to
 * actually work - a pure text/reasoning model will ignore the image. Swap
 * the property if the configured model doesn't support images; the calling
 * code and prompt format won't need to change.
 *
 * Non-streaming is used here (stream=false) since the backend needs the
 * complete answer before it can parse it into structured picks and hand it
 * back to the controller in one response.
 *
 * MODEL FALLBACK: each attempt against a given model gets a short timeout
 * (nvidia.attempt-timeout-seconds, default 5s). If a model doesn't respond
 * within that window (or errors out), the service automatically moves on to
 * the next model in the chain (primary model + up to 4 fallback models
 * configured via nvidia.fallback-models). The first model that returns
 * successfully wins; if every model in the chain fails, an ApiException is
 * thrown.
 */
@Service
public class NvidiaAiService {

    private static final Logger log = LoggerFactory.getLogger(NvidiaAiService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${nvidia.api-key:}")
    private String apiKey;

    @Value("${nvidia.base-url:https://integrate.api.nvidia.com/v1}")
    private String baseUrl;

    @Value("${nvidia.model:nvidia/nemotron-3-ultra-550b-a55b}")
    private String model;

    /**
     * Comma-separated list of fallback vision-capable models, tried in order
     * if the primary model times out or errors. Defaults to 4 fallbacks.
     */
    @Value("${nvidia.fallback-models:" +
            "meta/llama-3.2-90b-vision-instruct," +
            "meta/llama-3.2-11b-vision-instruct," +
            "microsoft/phi-3.5-vision-instruct," +
            "google/gemma-3-27b-it" +
            "}")
    private String fallbackModelsRaw;

    /** Per-attempt timeout: how long we wait on a single model before failing over. */
    @Value("${nvidia.attempt-timeout-seconds:5}")
    private long attemptTimeoutSeconds;

    private final WebClient.Builder webClientBuilder;

    public NvidiaAiService(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    public static class ScanAnalysis {
        public int totalPicksDetected;
        public List<PickPrediction> predictions = new ArrayList<>();
        public String rawModelOutput; // populated only if JSON parsing failed
        public String modelUsed;      // which model in the chain actually answered
    }

    /**
     * Sends the slip image + a plan-aware prompt to the model and parses the
     * result into structured picks, capped to plan.getMaxPicks() (unless the
     * plan is full coverage).
     *
     * @param imageBase64  raw base64 (no data-URI prefix) of the uploaded image
     * @param imageMediaType e.g. "image/jpeg", "image/png"
     */
    public ScanAnalysis analyzeSlip(String imageBase64, String imageMediaType, ScanPlan plan) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(
                    "AI scanning is not configured on the server (missing nvidia.api-key / NVIDIA_API_KEY).",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        String dataUri = "data:" + imageMediaType + ";base64," + imageBase64;
        String userPrompt = buildPrompt(plan);

        Map<String, Object> imageContent = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUri));
        Map<String, Object> textContent = Map.of(
                "type", "text",
                "text", userPrompt);

        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", List.of(textContent, imageContent));

        Map<String, Object> systemMessage = Map.of(
                "role", "system",
                "content",
                """
                You are Predator AI, an elite football betting analyst specializing in interpreting virtual betting slips from images.
        
                Your primary responsibility is to analyze uploaded virtual betting coupon images and accurately predict the most probable outcome of every football match displayed.
        
                Rules:
        
                • The uploaded image is a virtual football betting slip containing multiple fixtures.
                • Carefully inspect the image and identify every visible match.
                • For each fixture, predict exactly ONE outcome:
                  - Home Win (1)
                  - Draw (X)
                  - Away Win (2)
        
                • Base your predictions on:
                  - Betting odds shown on the coupon
                  - Relative implied probabilities
                  - Team strength if recognizable
                  - Statistical probability inferred from the odds
                  - Football reasoning and betting intelligence
        
                • Never simply choose the lowest odds automatically.
                • Consider whether the market suggests a possible upset or draw.
                • If the image quality prevents reading a match or odds, mark that fixture as "unreadable" instead of guessing.
        
                Return ONLY valid JSON.
        
                Do NOT include:
                - Markdown
                - Code fences
                - Explanations outside JSON
                - Extra text
        
                JSON format:
        
                {
                  "status": "success",
                  "totalMatches": 10,
                  "predictions": [
                    {
                      "matchNumber": 1,
                      "homeTeam": "ARS",
                      "awayTeam": "AST",
                      "odds": {
                        "home": 1.61,
                        "draw": 4.00,
                        "away": 5.56
                      },
                      "prediction": "1",
                      "winner": "ARS",
                      "confidence": 92,
                      "reason": "Home team has the strongest implied probability based on the betting market."
                    }
                  ],
                  "summary": {
                    "homeWins": 6,
                    "draws": 2,
                    "awayWins": 2,
                    "highestConfidenceMatch": 1
                  }
                }
        
                If any fixture cannot be read, return:
        
                {
                  "matchNumber": 4,
                  "status": "unreadable"
                }
        
                Never fabricate fixtures or odds that are not visible in the uploaded image.
                Always return valid JSON that can be parsed directly by a JSON parser.
                """
        );

        Map<String, Object> baseBody = new LinkedHashMap<>();
        baseBody.put("messages", List.of(systemMessage, userMessage));
        baseBody.put("temperature", 0.4);
        baseBody.put("top_p", 0.9);
        baseBody.put("max_tokens", 4096);
        baseBody.put("stream", false);

        List<String> modelChain = buildModelChain();

        Exception lastError = null;
        for (String candidateModel : modelChain) {
            try {
                Map<String, Object> body = new LinkedHashMap<>(baseBody);
                body.put("model", candidateModel);

                log.info("Attempting slip scan with model [{}] (timeout {}s)", candidateModel, attemptTimeoutSeconds);
                String content = callChatCompletions(body, candidateModel);

                ScanAnalysis analysis = parseModelResponse(content, plan);
                analysis.modelUsed = candidateModel;
                return analysis;
            } catch (Exception ex) {
                lastError = ex;
                log.warn("Model [{}] failed or timed out after {}s, falling back to next model. Reason: {}",
                        candidateModel, attemptTimeoutSeconds, ex.getMessage());
            }
        }

        // Every model in the chain failed.
        String message = "AI scanning failed: all " + modelChain.size() +
                " model(s) in the fallback chain timed out or errored" +
                (lastError != null ? (" (last error: " + lastError.getMessage() + ")") : "");
        throw new ApiException(message, HttpStatus.BAD_GATEWAY);
    }

    /** Builds the ordered list of models to try: primary model first, then up to 4 configured fallbacks. */
    private List<String> buildModelChain() {
        List<String> chain = new ArrayList<>();
        chain.add(model);

        if (fallbackModelsRaw != null && !fallbackModelsRaw.isBlank()) {
            List<String> fallbacks = Stream.of(fallbackModelsRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .filter(s -> !s.equals(model)) // avoid retrying the same model twice in a row
                    .collect(Collectors.toList());
            chain.addAll(fallbacks);
        }

        return chain;
    }

    private String buildPrompt(ScanPlan plan) {
        String coverageInstruction = plan.isFullCoverage()
                ? "Analyze EVERY pick/game/section on the slip (full coverage)."
                : "The user's plan only covers up to " + plan.getMaxPicks() + " picks. " +
                  "Analyze at most the first " + plan.getMaxPicks() + " picks/sections on the slip, " +
                  "in the order they appear, and leave the rest out entirely.";

        return "You are looking at an image of a sports betting slip/coupon. It contains one or more " +
                "individual picks/games (each pick is one \"section\" of the slip: teams, market, odds, etc). " +
                "\n\n1. Count and identify every distinct pick/section on the slip, in the order printed.\n" +
                "2. " + coverageInstruction + "\n" +
                "3. For each analyzed pick, give your own prediction (independent assessment, not just " +
                "restating the slip), a confidence level, and a short 1-3 sentence analysis.\n\n" +
                "Respond with ONLY a single JSON object, no other text, matching exactly this shape:\n" +
                "{\n" +
                "  \"totalPicksDetected\": <integer, total picks found on the whole slip>,\n" +
                "  \"picks\": [\n" +
                "    {\n" +
                "      \"sectionIndex\": <integer, 1-based order on the slip>,\n" +
                "      \"matchLabel\": \"<teams/event as read off the slip>\",\n" +
                "      \"originalPick\": \"<the selection/market printed on the slip, if legible>\",\n" +
                "      \"prediction\": \"<your own predicted outcome>\",\n" +
                "      \"confidence\": \"High\" | \"Medium\" | \"Low\",\n" +
                "      \"analysis\": \"<brief reasoning>\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    /**
     * Performs a single attempt against the given model. Uses a short
     * per-attempt timeout (attemptTimeoutSeconds) and NO internal retries -
     * on failure/timeout the caller (analyzeSlip) is responsible for moving
     * on to the next model in the fallback chain.
     */
    @SuppressWarnings("unchecked")
    private String callChatCompletions(Map<String, Object> body, String candidateModel) {
        Duration attemptTimeout = Duration.ofSeconds(attemptTimeoutSeconds);

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) webClientBuilder.build()
                    .post().uri(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody ->
                            new ApiException("AI provider [" + candidateModel + "] returned " + r.statusCode() +
                                    ": " + respBody, HttpStatus.BAD_GATEWAY)))
                    .bodyToMono(Map.class)
                    .timeout(attemptTimeout)
                    .retryWhen(Retry.max(0)) // no retry on same model - fail fast so we can fall over
                    .onErrorMap(ex -> !(ex instanceof ApiException),
                            ex -> new ApiException("Error contacting AI provider [" + candidateModel + "]: " +
                                    ex.getMessage(), HttpStatus.BAD_GATEWAY))
                    .block();
        } catch (ApiException ex) {
            throw ex;
        }

        if (result == null) {
            throw new ApiException("AI provider [" + candidateModel + "] returned an empty response.",
                    HttpStatus.BAD_GATEWAY);
        }

        List<Object> choices = (List<Object>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ApiException("AI provider [" + candidateModel + "] returned no choices.",
                    HttpStatus.BAD_GATEWAY);
        }

        Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);
        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        Object content = message != null ? message.get("content") : null;

        if (content == null) {
            throw new ApiException("AI provider [" + candidateModel + "] returned an empty message.",
                    HttpStatus.BAD_GATEWAY);
        }
        return content.toString();
    }

    private ScanAnalysis parseModelResponse(String content, ScanPlan plan) {
        ScanAnalysis analysis = new ScanAnalysis();

        String jsonText = extractJson(content);
        try {
            JsonNode root = objectMapper.readTree(jsonText);
            analysis.totalPicksDetected = root.path("totalPicksDetected").asInt(0);

            JsonNode picksNode = root.path("picks");
            int cap = plan.isFullCoverage() ? Integer.MAX_VALUE : plan.getMaxPicks();

            if (picksNode.isArray()) {
                int count = 0;
                for (JsonNode pickNode : picksNode) {
                    if (count >= cap) {
                        break;
                    }
                    PickPrediction pick = new PickPrediction();
                    pick.setSectionIndex(pickNode.path("sectionIndex").asInt(count + 1));
                    pick.setMatchLabel(pickNode.path("matchLabel").asText(""));
                    pick.setOriginalPick(pickNode.path("originalPick").asText(""));
                    pick.setPrediction(pickNode.path("prediction").asText(""));
                    pick.setConfidence(pickNode.path("confidence").asText(""));
                    pick.setAnalysis(pickNode.path("analysis").asText(""));
                    analysis.predictions.add(pick);
                    count++;
                }
            }

            if (analysis.totalPicksDetected == 0) {
                analysis.totalPicksDetected = analysis.predictions.size();
            }
        } catch (Exception ex) {
            // Model didn't return clean JSON - fall back to raw text so nothing is lost.
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
}
