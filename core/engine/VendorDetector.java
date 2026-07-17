package com.reiter.autostack.core.engine;

// 🚀 REPARACIÓN: Importamos el DTO analítico real desde tu paquete 'model'
import com.reiter.autostack.core.model.DetectionResult;

/**
 * Contrato para el motor de clasificación perimetral de documentos.
 */
public interface VendorDetector {

    /**
     * Analiza las huellas digitales heterogéneas de la factura.
     *
     * @param plainText El texto crudo del documento (acotado a la cabecera).
     * @return Un {@link DetectionResult} enriquecido con el diagnóstico del match.
     */
    DetectionResult detect(String plainText);
}