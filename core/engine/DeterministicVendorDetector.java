package com.reiter.autostack.core.engine;

import org.apache.commons.text.similarity.LevenshteinDistance;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.reiter.autostack.core.model.DetectionResult;
import com.reiter.autostack.core.model.DetectionFailureReason;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class DeterministicVendorDetector implements VendorDetector {
    private static final Logger log = LoggerFactory.getLogger(DeterministicVendorDetector.class);

    private static final int FINGERPRINT_WINDOW_CHARS = 3000;
    private final List<VendorFingerprint> catalog;
    private final MeterRegistry metrics;

    public interface VendorRule {
        int evaluate(String text);
        int maxScore();
    }

    public record VendorMatch(String vendorCode, int score, double confidence, double minimumConfidenceThreshold) implements Comparable<VendorMatch> {
        @Override
        public int compareTo(VendorMatch o) { return Double.compare(this.confidence, o.confidence); }
    }

    public record VendorFingerprint(String vendorCode, int version, double minimumConfidence, List<VendorRule> rules, int maxTheoreticalScore) {
        public VendorFingerprint(String vendorCode, double minimumConfidence, List<VendorRule> rules) {
            this(vendorCode, 1, minimumConfidence, List.copyOf(rules), rules.stream().mapToInt(VendorRule::maxScore).sum());
        }
        public VendorFingerprint(String vendorCode, int version, double minimumConfidence, List<VendorRule> rules) {
            this(vendorCode, version, minimumConfidence, List.copyOf(rules), rules.stream().mapToInt(VendorRule::maxScore).sum());
        }
    }

    public DeterministicVendorDetector(VendorFingerprintCatalog catalog, MeterRegistry metrics) {
        this.catalog = catalog.getFingerprints();
        this.metrics = metrics;
    }

    @Override
    public DetectionResult detect(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return DetectionResult.failed(DetectionFailureReason.UNKNOWN_VENDOR);
        }

        String fingerprintWindow = plainText.substring(0, Math.min(FINGERPRINT_WINDOW_CHARS, plainText.length()));

        List<VendorMatch> matches = catalog.stream()
                .map(fingerprint -> {
                    int positiveScore = 0;
                    int negativePenalty = 0;

                    for (VendorRule rule : fingerprint.rules()) {
                        int scoreResult = rule.evaluate(fingerprintWindow);
                        if (scoreResult > 0) {
                            positiveScore += scoreResult;
                        } else if (scoreResult < 0) {
                            negativePenalty += Math.abs(scoreResult);
                        }
                    }

                    int effectiveScore = Math.max(0, positiveScore - negativePenalty);
                    int maxScore = fingerprint.maxTheoreticalScore();
                    double confidence = maxScore > 0 ? (double) effectiveScore / maxScore : 0.0;

                    metrics.summary("autostack.vendor.match.confidence",
                                    "vendor", fingerprint.vendorCode(),
                                    "version", String.valueOf(fingerprint.version()))
                            .record(confidence);

                    return new VendorMatch(
                            fingerprint.vendorCode(),
                            effectiveScore,
                            Math.min(1.0, confidence),
                            fingerprint.minimumConfidence()
                    );
                })
                .filter(m -> m.confidence() >= m.minimumConfidenceThreshold())
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());

        if (matches.isEmpty()) {
            return DetectionResult.failed(DetectionFailureReason.UNKNOWN_VENDOR);
        }

        VendorMatch winner = matches.get(0);

        if (matches.size() > 1) {
            VendorMatch runnerUp = matches.get(1);
            double deltaConfidence = winner.confidence() - runnerUp.confidence();

            if (deltaConfidence < 0.15) {
                log.warn("[DETECTOR_AMBIGUITY] Soft collision. Winner '{}' vs RunnerUp '{}'. Deferring to Gemini.",
                        winner.vendorCode(), runnerUp.vendorCode());
                return DetectionResult.failed(DetectionFailureReason.AMBIGUOUS_MATCH);
            }
        }

        if (winner.confidence() < winner.minimumConfidenceThreshold()) {
            return DetectionResult.failed(DetectionFailureReason.LOW_CONFIDENCE);
        }

        log.info("[DETECTOR_SUCCESS] Fingerprint verified for '{}' (Confidence: {}%)",
                winner.vendorCode(), String.format("%.1f", winner.confidence() * 100));

        return DetectionResult.success(winner.vendorCode(), winner.confidence());
    }

    // =======================================================================================
    // 🧠 REGLAS HETEROGÉNEAS HARDENED V5
    // =======================================================================================

    public record RegexRule(Pattern pattern, int score) implements VendorRule {
        @Override public int evaluate(String text) { return pattern.matcher(text).find() ? score : 0; }
        @Override public int maxScore() { return score; }
    }

    public record FuzzyRule(String keyword, int maxDistance, int score) implements VendorRule {
        private static final LevenshteinDistance LEV = LevenshteinDistance.getDefaultInstance();

        @Override
        public int evaluate(String text) {
            if (text == null || text.isEmpty()) return 0;
            String normalizedText = text.toUpperCase().replaceAll("\\s+", " ");
            String normalizedKeyword = keyword.toUpperCase().trim();

            if (normalizedText.contains(normalizedKeyword)) return score;

            String[] keywordWords = normalizedKeyword.split(" ");

            boolean sharesRootTokens = Arrays.stream(keywordWords)
                    .anyMatch(w -> normalizedText.contains(w.substring(0, Math.min(3, w.length()))));
            if (!sharesRootTokens) return 0;

            String[] textWords = normalizedText.split(" ");
            int targetWordCount = keywordWords.length;
            if (textWords.length < targetWordCount) return 0;

            for (int i = 0; i <= textWords.length - targetWordCount; i++) {
                StringBuilder windowBuilder = new StringBuilder();
                for (int j = 0; j < targetWordCount; j++) {
                    if (j > 0) windowBuilder.append(" ");
                    windowBuilder.append(textWords[i + j]);
                }

                String currentWindowPhrase = windowBuilder.toString();
                if (LEV.apply(currentWindowPhrase, normalizedKeyword) <= maxDistance) {
                    return score;
                }
            }
            return 0;
        }
        @Override public int maxScore() { return score; }
    }

    public record AnchorRule(List<String> anchors, int proximityCharLimit, int score) implements VendorRule {
        @Override
        public int evaluate(String text) {
            if (text == null || text.isEmpty() || anchors.isEmpty()) return 0;
            String normalizedText = text.toUpperCase();

            List<Integer> positions = new ArrayList<>();
            for (String anchor : anchors) {
                int index = normalizedText.indexOf(anchor.toUpperCase());
                if (index == -1) return 0;
                positions.add(index);
            }

            int minPosition = Collections.min(positions);
            int maxPosition = Collections.max(positions);

            if ((maxPosition - minPosition) <= proximityCharLimit) {
                return score;
            }
            return 0;
        }
        @Override public int maxScore() { return score; }
    }

    public record NegativeRule(Pattern pattern, int penaltyScore) implements VendorRule {
        @Override public int evaluate(String text) { return pattern.matcher(text).find() ? penaltyScore : 0; }
        @Override public int maxScore() { return 0; }
    }
}