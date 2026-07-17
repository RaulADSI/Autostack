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
public class FplExtractor implements TemplateExtractor {
    private static final Logger log = LoggerFactory.getLogger(FplExtractor.class);

    // 🔧 Matriz de firmas institucionales extraídas del diagnóstico de FPL
    private static final List<String> FPL_SIGNATURES = List.of(
            "FPL.COM",
            "CURRENT BILL",
            "TOTAL AMOUNT YOU OWE",
            "BILL SUMMARY",
            "NEW CHARGES DUE BY"
    );

    // Expresión regular ajustada para capturar "Statement Date: May 19, 2026"
    private static final Pattern INVOICE_DATE = Pattern.compile(
            "Statement\\s+Date:\\s*([A-Z][a-z]{2,8}\\s+\\d{1,2},\\s*\\d{4})",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ACCOUNT_NUMBER = Pattern.compile(
            "Account\\s+Number:\\s*([0-9\\-]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CURRENT_BILL_AMT = Pattern.compile(
            "CURRENT\\s+BILL\\s*\\$\\s*([0-9,]+\\.\\d{2})",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TOTAL_OWE_AMT = Pattern.compile(
            "Total\\s+amount\\s+you\\s+owe\\s*\\$?([0-9,]+\\.\\d{2})",
            Pattern.CASE_INSENSITIVE
    );
    @Override
    public int calculateMatchScore(String text) {
        String upperText = text.toUpperCase();
        int signatureHits = 0;

        for (String signature : FPL_SIGNATURES) {
            if (upperText.contains(signature)) signatureHits++;
        }

        // Si golpea al menos 2 firmas clave, le otorgamos los 30 puntos de asignación de plantilla
        return (signatureHits >= 2) ? 30 : 0;
    }

    @Override
    public ExtractionResult extract(String text, int matchScore) {
        if (matchScore < 30) return ExtractionResult.unknown();

        int finalScore = matchScore; // Inicia con 30 puntos base

        // 1. Extraer Cuenta (Valor: 25)
        // Intentamos con la regex explícita; si falla pero el archivo se llama como la cuenta, usamos fallback posicional
        String account = extractField(text, ACCOUNT_NUMBER);
        if (account.isEmpty()) {
            // Fallback de contingencia si viene solo el formato numérico suelto en el texto
            account = extractField(text, Pattern.compile("(\\d{5}-\\d{5})"));
        }
        double accountConf = !account.isEmpty() ? 1.0 : 0.0;
        if (accountConf > 0) finalScore += 25;

        // 2. Extraer Factura (FPL no maneja un número de factura tradicional independiente de la cuenta)
        String invoice = "NOT_APPLICABLE";
        finalScore += 20; // Otorgamos el puntaje por diseño estructural de cuenta única

        // 3. Extraer Fecha (Valor: 5)
        String date = extractField(text, INVOICE_DATE);
        double dateConf = !date.isEmpty() ? 1.0 : 0.0;
        if (dateConf > 0) finalScore += 5;

        // 4. Extraer Monto con Cascada y Trazabilidad de Estrategia
        String rawAmount = extractField(text, CURRENT_BILL_AMT);
        String amountSource = "CURRENT_BILL";
        double amountConf = 1.0;

        if (rawAmount.isEmpty()) {
            rawAmount = extractField(text, TOTAL_OWE_AMT);
            amountSource = "TOTAL_AMOUNT_YOU_WE";
            amountConf = 0.9;
        }

        double amount = 0.0;
        if (!rawAmount.isEmpty()) {
            amount = parseAmount(rawAmount);
            if (amount > 0.0) {
                finalScore += 20;
            } else {
                amountConf = 0.0;
            }
        } else {
            amountConf = 0.0;
            amountSource = "NONE";
        }

        log.debug("[AUDIT_INTELLIGENCE] Vendor 'FPL' selected amount strategy: {} (Value: {})", amountSource, rawAmount);

        // Cierre de seguridad de escala
        finalScore = Math.min(finalScore, 100);

        return new ExtractionResult(
                "FPL",
                new FieldConfidence<>(account.isEmpty() ? "NOT_FOUND" : account, accountConf),
                new FieldConfidence<>(invoice, 1.0),
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
            if (cleanValue.startsWith("(") && cleanValue.endsWith(")")) {
                cleanValue = "-" + cleanValue.substring(1, cleanValue.length() - 1);
            }
            return Double.parseDouble(cleanValue);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}