package com.reiter.autostack.intelligence;

import com.reiter.autostack.core.model.ExtractionResult;
import com.reiter.autostack.core.model.FieldConfidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WmExtractor implements TemplateExtractor{
    private static final Logger log = LoggerFactory.getLogger(WmExtractor.class);

    // CORRECCIÓN 2: Firmas en MAYÚSCULAS para comparación agnóstica estandarizada
    private static final List<String> WM_SIGNATURES = List.of(
            "WM CORPORATE SERVICES",
            "GEORGIA WASTE SYSTEMS",
            "CUSTOMER ID:",
            "INVOICE NUMBER:",
            "YOUR TOTAL DUE",
            "WM.COM/MYWM"
    );

    // CORRECCIÓN 3: Conservar los dos puntos para máxima precisión posicional
    private static final Pattern CUSTOMER_ID = Pattern.compile("Customer\\s+ID:\\s*([0-9\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INVOICE_NUMBER = Pattern.compile("Invoice\\s+Number:\\s*([0-9\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INVOICE_DATE = Pattern.compile("Invoice\\s+Date:\\s*(\\d{2}/\\d{2}/\\d{4})", Pattern.CASE_INSENSITIVE);

    private static final Pattern TOTAL_DUE = Pattern.compile("Your\\s+Total\\s+Due\\s*\\$?([0-9,]+\\.\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENT_CHARGES = Pattern.compile("Total\\s+Current\\s+Charges\\s*([0-9,]+\\.\\d{2})", Pattern.CASE_INSENSITIVE);

    @Override
    public int calculateMatchScore(String text) {
        // CORRECCIÓN 2: Forzar mayúsculas antes de evaluar la existencia de firmas de identidad
        String upperText = text.toUpperCase();
        int signatureHits = 0;

        for (String signature : WM_SIGNATURES) {
            if (upperText.contains(signature)) signatureHits++;
        }

        return (signatureHits >= 3) ? 30 : 0;
    }

    @Override
    public ExtractionResult extract(String text, int matchScore) {
        // CORRECCIÓN 1: Consistencia semántica estricta sobre el umbral de firmas
        if (matchScore < 30) return ExtractionResult.unknown();

        int finalScore = matchScore; // Inicia con los 30 puntos base seguros

        // 1. Extraer Cuenta (Valor: 25)
        String account = extractField(text, CUSTOMER_ID);
        double accountConf = !account.isEmpty() ? 1.0 : 0.0;
        if (accountConf > 0) finalScore += 25;

        // 2. Extraer Factura (Valor: 20)
        String invoice = extractField(text, INVOICE_NUMBER);
        double invoiceConf = !invoice.isEmpty() ? 1.0 : 0.0;
        if (invoiceConf > 0) finalScore += 20;

        // 3. Extraer Fecha (Valor: 5)
        String date = extractField(text, INVOICE_DATE);
        double dateConf = !date.isEmpty() ? 1.0 : 0.0;
        if (dateConf > 0) finalScore += 5;

        // 4. Extraer Monto en Cascada con Trazabilidad Estructurada
        String rawAmount = extractField(text, TOTAL_DUE);
        String amountSource = "TOTAL_DUE"; // 🔧 CORRECCIÓN 4: Auditoría de estrategia
        double amountConf = 1.0;

        if (rawAmount.isEmpty()) {
            rawAmount = extractField(text, CURRENT_CHARGES);
            amountSource = "CURRENT_CHARGES";
            amountConf = 0.8; // Penalización táctica por fallback descriptivo
        }

        double amount = 0.0;
        if (!rawAmount.isEmpty()) {
            // 🔧 CORRECCIÓN 5: Conversión a través del helper centralizado robusto
            amount = parseAmount(rawAmount);
            if (amount > 0.0 || text.contains("Credit") || rawAmount.contains("-")) {
                finalScore += 20;
            } else {
                amountConf = 0.0;
            }
        } else {
            amountConf = 0.0;
            amountSource = "NONE";
        }

        log.debug("[AUDIT_INTELLIGENCE] Vendor 'WM' selected amount strategy: {} (Value: {})", amountSource, rawAmount);

        // CORRECCIÓN 6: Cierre de seguridad matemático para no romper la escala del 100%
        finalScore = Math.min(finalScore, 100);

        return new ExtractionResult(
                "WM",
                new FieldConfidence<>(account.isEmpty() ? "NOT_FOUND" : account, accountConf),
                new FieldConfidence<>(invoice.isEmpty() ? "NOT_FOUND" : invoice, invoiceConf),
                new FieldConfidence<>(date.isEmpty() ? "NOT_FOUND" : date, dateConf),
                new FieldConfidence<>(amount, amountConf),
                finalScore
        );
    }

    private String extractField(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) return matcher.group(1).trim();
        return "";
    }


    private double parseAmount(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            String cleanValue = value.replace(",", "").trim();

            // Tratamiento defensivo para formatos contables negativos: (445.13) -> -445.13
            if (cleanValue.startsWith("(") && cleanValue.endsWith(")")) {
                cleanValue = "-" + cleanValue.substring(1, cleanValue.length() - 1);
            }

            return Double.parseDouble(cleanValue);
        } catch (NumberFormatException e) {
            log.error("[MONETARY_PARSE_CRASH] Failed to clean and parse financial token '{}': {}", value, e.getMessage());
            return 0.0;
        }
    }
}
