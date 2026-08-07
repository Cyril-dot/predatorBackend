package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.PickPrediction;
import com.example.subscription.model.ScanPlan;
import com.example.subscription.model.ScanPurchase;
import com.example.subscription.model.ScanResult;
import com.example.subscription.repository.InMemoryScanResultRepository;
import com.example.subscription.util.CodeGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Runs the actual AI scan for an approved {@link ScanPurchase} and stores the
 * result. Each purchase can only be used once - calling analyze() a second
 * time for the same purchase id fails.
 */
@Service
public class ScanService {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final ScanPurchaseService scanPurchaseService;
    private final NvidiaAiService nvidiaAiService;
    private final InMemoryScanResultRepository scanResultRepository;

    public ScanService(ScanPurchaseService scanPurchaseService,
                       NvidiaAiService nvidiaAiService,
                       InMemoryScanResultRepository scanResultRepository) {
        this.scanPurchaseService = scanPurchaseService;
        this.nvidiaAiService = nvidiaAiService;
        this.scanResultRepository = scanResultRepository;
    }

    public synchronized ScanResult analyze(String purchaseId, String email, MultipartFile image) {
        ScanPurchase purchase = scanPurchaseService.getById(purchaseId);

        if (!purchase.getEmail().equalsIgnoreCase(email)) {
            throw new ApiException("This scan purchase does not belong to " + email, HttpStatus.FORBIDDEN);
        }
        if (!purchase.isReadyToScan()) {
            throw new ApiException(
                    "This scan purchase is not ready to use (status: " + purchase.getStatus() +
                            "). It must be APPROVED by an admin first, and can only be used once.",
                    HttpStatus.CONFLICT);
        }
        if (image == null || image.isEmpty()) {
            throw new ApiException("An image file is required", HttpStatus.BAD_REQUEST);
        }

        String contentType = image.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new ApiException(
                    "Unsupported image type. Allowed: image/jpeg, image/png, image/webp.",
                    HttpStatus.BAD_REQUEST);
        }

        String base64;
        try {
            base64 = Base64.getEncoder().encodeToString(image.getBytes());
        } catch (IOException ex) {
            throw new ApiException("Could not read uploaded image: " + ex.getMessage(), HttpStatus.BAD_REQUEST);
        }

        ScanPlan plan = purchase.getScanPlan();

        // Consume the purchase up front so a slow/failed AI call can't be retried for a free second scan.
        scanPurchaseService.markUsed(purchaseId);

        NvidiaAiService.ScanAnalysis analysis = nvidiaAiService.analyzeSlip(base64, contentType, plan);

        ScanResult result = new ScanResult();
        result.setId(CodeGenerator.generateId());
        result.setPurchaseId(purchaseId);
        result.setEmail(email);
        result.setScanPlan(plan);
        result.setTotalPicksDetected(analysis.totalPicksDetected);

        List<PickPrediction> predictions = analysis.predictions;
        result.setPredictions(predictions);
        result.setPicksAnalyzed(predictions.size());
        result.setRawModelOutput(analysis.rawModelOutput);

        result.setCoverageNote(buildCoverageNote(plan, analysis.totalPicksDetected, predictions.size()));

        scanResultRepository.save(result);
        return result;
    }

    private String buildCoverageNote(ScanPlan plan, int totalDetected, int analyzed) {
        if (plan.isFullCoverage()) {
            return "Full coverage plan - all " + analyzed + " detected pick(s) analyzed.";
        }
        if (totalDetected > analyzed) {
            return analyzed + " of " + totalDetected + " picks covered on the " + plan.name() +
                    " plan. Upgrade to a higher tier for more picks per slip.";
        }
        return analyzed + " of " + totalDetected + " picks covered.";
    }

    public ScanResult getById(String id) {
        return scanResultRepository.findById(id)
                .orElseThrow(() -> new ApiException("Scan result not found", HttpStatus.NOT_FOUND));
    }

    public ScanResult getByPurchaseId(String purchaseId) {
        return scanResultRepository.findByPurchaseId(purchaseId)
                .orElseThrow(() -> new ApiException("No scan result yet for this purchase", HttpStatus.NOT_FOUND));
    }

    public List<ScanResult> listForEmail(String email) {
        return scanResultRepository.findByEmail(email);
    }
}
