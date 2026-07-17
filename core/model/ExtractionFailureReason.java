package com.reiter.autostack.core.model;

public enum ExtractionFailureReason { // 👈 Debe decir ENUM, no class
    NONE,
    DETECTOR_UNKNOWN_VENDOR,
    EXTRACTOR_NOT_REGISTERED,
    EXTRACTOR_MISSING_CRITICAL_FIELDS,
    EXTRACTOR_LAYOUT_DRIFT,
    EXTRACTOR_LOW_CONFIDENCE
}