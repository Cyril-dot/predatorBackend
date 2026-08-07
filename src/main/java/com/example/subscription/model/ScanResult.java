package com.example.subscription.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The stored result of one AI betting-slip scan, tied 1:1 to the
 * {@link ScanPurchase} that paid for it.
 */
public class ScanResult {

    private String id;
    private String purchaseId;
    private String email;
    private ScanPlan scanPlan;

    private int totalPicksDetected;   // how many picks the AI found on the slip overall
    private int picksAnalyzed;        // how many it actually returned predictions for (capped by plan)
    private List<PickPrediction> predictions;
    private String coverageNote;      // e.g. "2 of 6 picks covered - upgrade to STANDARD or PREMIUM for more"
    private String rawModelOutput;    // fallback: full raw text if structured parsing failed
    private LocalDateTime createdAt;

    public ScanResult() {
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPurchaseId() {
        return purchaseId;
    }

    public void setPurchaseId(String purchaseId) {
        this.purchaseId = purchaseId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public ScanPlan getScanPlan() {
        return scanPlan;
    }

    public void setScanPlan(ScanPlan scanPlan) {
        this.scanPlan = scanPlan;
    }

    public int getTotalPicksDetected() {
        return totalPicksDetected;
    }

    public void setTotalPicksDetected(int totalPicksDetected) {
        this.totalPicksDetected = totalPicksDetected;
    }

    public int getPicksAnalyzed() {
        return picksAnalyzed;
    }

    public void setPicksAnalyzed(int picksAnalyzed) {
        this.picksAnalyzed = picksAnalyzed;
    }

    public List<PickPrediction> getPredictions() {
        return predictions;
    }

    public void setPredictions(List<PickPrediction> predictions) {
        this.predictions = predictions;
    }

    public String getCoverageNote() {
        return coverageNote;
    }

    public void setCoverageNote(String coverageNote) {
        this.coverageNote = coverageNote;
    }

    public String getRawModelOutput() {
        return rawModelOutput;
    }

    public void setRawModelOutput(String rawModelOutput) {
        this.rawModelOutput = rawModelOutput;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
