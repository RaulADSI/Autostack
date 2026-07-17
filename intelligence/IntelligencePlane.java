package com.reiter.autostack.intelligence;

import com.reiter.autostack.core.model.ExtractionResult;

public interface IntelligencePlane {

    ExtractionResult analyze(String plainTex);

    abstract class InferenceException extends RuntimeException {
        public InferenceException(String message) { super(message); }
        public InferenceException(String message, Throwable cause) { super(message, cause); }
    }

    class RetryableInferenceException extends InferenceException {
        public RetryableInferenceException(String message, Throwable cause) { super(message, cause); }
    }

    class PermanentInferenceException extends RuntimeException { // Runtime para integrarse limpio a streams
        public PermanentInferenceException(String message) { super(message); }
        public PermanentInferenceException(String message, Throwable cause) { super(message, cause); }
    }
}
