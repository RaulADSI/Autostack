package com.reiter.autostack.core.model;

public record ExtractionResult(
        String vendor,
        FieldConfidence<String> accountNumber,
        FieldConfidence<String> invoiceNumber, //

        // Añadido para observabilidad estructural
        FieldConfidence<String> invoiceDate,   //  Añadido para observabilidad estructural
        FieldConfidence<Double> amount,
        int strategyMatchScore
) {
    public static ExtractionResult unknown() {
        return new ExtractionResult(
                "UNKNOWN",
                new FieldConfidence<>("NOT_FOUND", 0.0),
                new FieldConfidence<>("NOT_FOUND", 0.0),
                new FieldConfidence<>("NOT_FOUND", 0.0),
                new FieldConfidence<>(0.0, 0.0),
                0
        );
    }
}