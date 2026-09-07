package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.model.PickPrediction;
import com.example.subscription.model.ScanResult;
import com.example.subscription.service.ScanService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs the actual AI scan of a betting-slip image against an APPROVED
 * {@code ScanPurchase}. One purchase = one scan; calling /analyze again for
 * the same purchase id will fail once it's been used.
 */
@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    @PostMapping(value = "/analyze", consumes = "multipart/form-data")
    public ApiResponse<Object> analyze(
            @RequestParam String purchaseId,
            @RequestParam String email,
            @RequestParam("image") MultipartFile image) {

        ScanResult result = scanService.analyze(purchaseId, email, image);
        return ApiResponse.ok("Scan complete", toResponse(result));
    }

    @GetMapping("/result/{id}")
    public ApiResponse<Object> getResult(@PathVariable String id) {
        return ApiResponse.ok("Scan result", toResponse(scanService.getById(id)));
    }

    @GetMapping("/history")
    public ApiResponse<Object> history(@RequestParam String email) {
        List<Map<String, Object>> list = scanService.listForEmail(email).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ApiResponse.ok("Scan history for " + email, list);
    }

    private Map<String, Object> toResponse(ScanResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("purchaseId", r.getPurchaseId());
        m.put("email", r.getEmail());
        m.put("scanPlan", r.getScanPlan().name());
        m.put("totalPicksDetected", r.getTotalPicksDetected());
        m.put("picksAnalyzed", r.getPicksAnalyzed());
        m.put("coverageNote", r.getCoverageNote());
        m.put("predictions", r.getPredictions() == null ? List.of() : r.getPredictions().stream()
                .map(this::toPickMap).collect(Collectors.toList()));
        if (r.getRawModelOutput() != null) {
            m.put("rawModelOutput", r.getRawModelOutput());
            m.put("note", "AI response could not be parsed into structured picks - raw output included instead.");
        }
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private Map<String, Object> toPickMap(PickPrediction p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sectionIndex", p.getSectionIndex());
        m.put("matchLabel", p.getMatchLabel());
        m.put("originalPick", p.getOriginalPick());
        m.put("prediction", p.getPrediction());
        m.put("confidence", p.getConfidence());
        m.put("analysis", p.getAnalysis());
        return m;
    }
}
