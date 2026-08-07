package com.example.subscription.model;

/**
 * The AI's prediction for a single pick/game/section found on the scanned
 * betting slip image.
 */
public class PickPrediction {

    private int sectionIndex;      // order it appeared on the slip, 1-based
    private String matchLabel;     // e.g. "Arsenal vs Chelsea" as read off the slip
    private String originalPick;   // the selection printed on the slip, if legible
    private String prediction;     // the AI's own prediction/verdict for this pick
    private String confidence;     // e.g. "High" / "Medium" / "Low", model's own wording
    private String analysis;       // short reasoning behind the prediction

    public PickPrediction() {
    }

    public PickPrediction(int sectionIndex, String matchLabel, String originalPick,
                          String prediction, String confidence, String analysis) {
        this.sectionIndex = sectionIndex;
        this.matchLabel = matchLabel;
        this.originalPick = originalPick;
        this.prediction = prediction;
        this.confidence = confidence;
        this.analysis = analysis;
    }

    public int getSectionIndex() {
        return sectionIndex;
    }

    public void setSectionIndex(int sectionIndex) {
        this.sectionIndex = sectionIndex;
    }

    public String getMatchLabel() {
        return matchLabel;
    }

    public void setMatchLabel(String matchLabel) {
        this.matchLabel = matchLabel;
    }

    public String getOriginalPick() {
        return originalPick;
    }

    public void setOriginalPick(String originalPick) {
        this.originalPick = originalPick;
    }

    public String getPrediction() {
        return prediction;
    }

    public void setPrediction(String prediction) {
        this.prediction = prediction;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getAnalysis() {
        return analysis;
    }

    public void setAnalysis(String analysis) {
        this.analysis = analysis;
    }
}
