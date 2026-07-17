package com.reiter.autostack.core.engine;


import com.reiter.autostack.core.model.ExtractionResult;
import com.reiter.autostack.core.model.ExtractionAssessment;

/**
 * Contrato unificado para todos los extractores locales basados en reglas.
 */
public interface DeterministicExtractor {

    /**
     * Devuelve el código único del proveedor que este extractor sabe procesar.
     */
    String getVendorCode();

    /**
     * Ejecuta el raspado de datos planos del texto (Hechos).
     */
    ExtractionResult extract(String plainText);

    /**
     * 🛡️ ADICIÓN EN V5: Dictamina de forma soberana el veredicto de calidad e integridad (Juicios),
     * aislando la lógica de Layout Drift y auditoría matemática de saldos.
     */
    ExtractionAssessment assess(ExtractionResult result, String plainText);
}