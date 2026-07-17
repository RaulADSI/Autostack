package com.reiter.autostack.intelligence;

import com.reiter.autostack.core.model.ExtractionResult;

public interface TemplateExtractor {
    int calculateMatchScore(String normalizedText);
    ExtractionResult extract(String normalizedText, int matchScore);
}
