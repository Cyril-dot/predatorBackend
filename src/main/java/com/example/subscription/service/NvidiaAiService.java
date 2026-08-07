package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.PickPrediction;
import com.example.subscription.model.ScanPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Service
public class NvidiaAiService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Duration timeout = Duration.ofSeconds(75);

    @Value("${nvidia.api-key:}")
    private String apiKey;

    @Value("${nvidia.base-url:https://integrate.api.nvidia.com/v1}")
    private String baseUrl;

    @Value("${nvidia.model:nvidia/nemotron-3-ultra-550b-a55b}")
    private String model;

    private final WebClient.Builder webClientBuilder;

    public NvidiaAiService(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    public static class ScanAnalysis {
        public int totalPicksDetected;
        public List<PickPrediction> predictions = new ArrayList<>();
        public String rawModelOutput; // populated only if JSON parsing failed
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
                "content", "You are Predator AI, a disciplined sports-betting slip analyst. " +
                        "You always respond with strict, valid JSON only - no markdown, no code fences, " +
                        "no commentary outside the JSON object.");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(systemMessage, userMessage));
        body.put("temperature", 0.4);
        body.put("top_p", 0.9);
        body.put("max_tokens", 4096);
        body.put("stream", false);

        String content = callChatCompletions(body);
        return parseModelResponse(content, plan);
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

    @SuppressWarnings("unchecked")
    private String callChatCompletions(Map<String, Object> body) {
        Map<String, Object> result;
        try {
            result = (Map<String, Object>) webClientBuilder.build()
                    .post().uri(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody ->
                            new ApiException("AI provider returned " + r.statusCode() + ": " + respBody,
                                    HttpStatus.BAD_GATEWAY)))
                    .bodyToMono(Map.class)
                    .timeout(timeout)
                    .retryWhen(Retry.max(1).filter(ex -> !(ex instanceof ApiException)))
                    .onErrorMap(ex -> !(ex instanceof ApiException),
                            ex -> new ApiException("Error contacting AI provider: " + ex.getMessage(),
                                    HttpStatus.BAD_GATEWAY))
                    .block();
        } catch (ApiException ex) {
            throw ex;
        }

        if (result == null) {
            throw new ApiException("AI provider returned an empty response.", HttpStatus.BAD_GATEWAY);
        }

        List<Object> choices = (List<Object>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ApiException("AI provider returned no choices.", HttpStatus.BAD_GATEWAY);
        }

        Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);
        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        Object content = message != null ? message.get("content") : null;

        if (content == null) {
            throw new ApiException("AI provider returned an empty message.", HttpStatus.BAD_GATEWAY);
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
