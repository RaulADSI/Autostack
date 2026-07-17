package com.reiter.autostack.core.model;

public enum InvoiceStatus {
    NEW,
    PROCESSING,
    AI_PROCESSING,   // Estado temporal: Bloqueo transaccional de orquestación cognitiva
    REVIEW_REQUIRED, // Estado humano: Aislamiento para excepciones ambiguas
    SENT,
    FAILED,
    DEAD
}