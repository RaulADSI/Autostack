package com.reiter.autostack.core.model;

public record FieldConfidence<T>(
        T value,
        double confidence
) {}
