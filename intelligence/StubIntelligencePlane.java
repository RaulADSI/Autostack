package com.reiter.autostack.intelligence;

import com.reiter.autostack.core.model.ExtractionResult;
import com.reiter.autostack.core.model.FieldConfidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StubIntelligencePlane implements IntelligencePlane {
    private static final Logger log = LoggerFactory.getLogger(StubIntelligencePlane.class);

    @Override
    public ExtractionResult analyze(String plainText) {
        log.info("[INTELLIGENCE_STUB] Simulating LLM deep analysis on unmapped layout...");

        return new ExtractionResult(
                "AI_SIMULATION",
                new FieldConfidence<>("REVIEW_ACCOUNT", 0.75),
                new FieldConfidence<>("REVIEW_INVOICE", 0.75),
                new FieldConfidence<>("REVIEW_DATE", 0.75),
                new FieldConfidence<>(123.45, 0.75),
                85
        );
    }
}