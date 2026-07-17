package com.reiter.autostack.core.model;

public record DetectionResult(
        boolean matched,
        String vendorCode,
        DetectionFailureReason reason,
        double confidence
) {
    public static DetectionResult success(String vendorCode, double confidence) {
        return new DetectionResult(true, vendorCode, DetectionFailureReason.NONE, confidence);
    }

    public static DetectionResult failed(DetectionFailureReason reason) {
        return new DetectionResult(false, "UNKNOWN", reason, 0.0);
    }
}