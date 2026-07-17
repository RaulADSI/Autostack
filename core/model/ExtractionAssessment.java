package com.reiter.autostack.core.model;


public record ExtractionAssessment(
        boolean valid,
        boolean layoutDrift,
        double confidence,
        ExtractionFailureReason failureReason
) {
    /**
     * Genera un dictamen perfecto para promover el documento por la pista rápida.
     */
    public static ExtractionAssessment perfect() {
        return new ExtractionAssessment(true, false, 1.0, ExtractionFailureReason.NONE);
    }

    /**
     * Genera un dictamen degradado disparando las alertas de desvío de diseño.
     */
    public static ExtractionAssessment degraded(ExtractionFailureReason reason) {
        return new ExtractionAssessment(false, true, 0.0, reason);
    }
}