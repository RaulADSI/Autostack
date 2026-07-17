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
public class MiamiDadeExtractor implements TemplateExtractor {
    private static final Logger log = LoggerFactory.getLogger(MiamiDadeExtractor.class);

    // Matriz de firmas institucionales del condado
    private static final List<String> WASD_SIGNATURES = List.of(
            "MIAMI-DADE WATER AND SEWER",
            "WWW.MIAMIDADE.GOV/WATER",
            "WATER CONSERVATION PROGRAM",
            "TOTAL ACCOUNT BALANCE",
            "HYDRANT CHARGE"
    );

    // Expresiones regulares basadas en la evidencia topológica
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("Account\\s+Number:?\\s*([0-9\\-]+)", Pattern.CASE_INSENSITIVE);

    // 🔧 CORRECCIÓN: Patrón elástico para tolerar variaciones con/sin dos puntos y espaciado continuo
    private static final Pattern BILLING_DATE = Pattern.compile("Billing\\s+Date\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})", Pattern.CASE_INSENSITIVE);

    // 🎯 ESTRATEGIA EN CASCADA REORDENADA (Puntos de anclaje optimizados)
    private static final Pattern AMOUNT_DUE = Pattern.compile("Amount\\s+Due.*?\\$\\s*([0-9,]+\\.\\d{2})", Pattern.CASE_INSENSITIVE); // 🔧 Primaria
    private static final Pattern TOTAL_BALANCE = Pattern.compile("Total\\s+Account\\s+Balance\\s*\\$\\s*([0-9,]+\\.\\d{2})", Pattern.CASE_INSENSITIVE); // Secundaria
    private static final Pattern CURRENT_CHARGES = Pattern.compile("Current\\s+Charges\\s*\\$?([0-9,]+\\.\\d{2})", Pattern.CASE_INSENSITIVE); // Contingencia

    @Override
    public int calculateMatchScore(String text) {
        String upperText = text.toUpperCase();
        int signatureHits = 0;

        for (String signature : WASD_SIGNATURES) {
            if (upperText.contains(signature)) signatureHits++;
        }

        return (signatureHits >= 2) ? 30 : 0;
    }

    @Override
    public ExtractionResult extract(String text, int matchScore) {
        if (matchScore < 30) return ExtractionResult.unknown();

        int finalScore = matchScore;

        // 1. Extraer Cuenta (Valor: 25)
        String account = extractField(text, ACCOUNT_NUMBER);
        double accountConf = !account.isEmpty() ? 1.0 : 0.0;
        if (accountConf > 0) finalScore += 25;

        // 2. Extraer Factura (Diseño estructural para utilities sin Invoice ID)
        String invoice = "NOT_APPLICABLE";
        finalScore += 20;

        // 3. Extraer Fecha (Valor: 5)
        String date = extractField(text, BILLING_DATE);
        double dateConf = !date.isEmpty() ? 1.0 : 0.0;
        if (dateConf > 0) finalScore += 5;

        // 4. Extraer Monto con Cascada Ponderada Avanzada (Valor: 20)
        String rawAmount = extractField(text, AMOUNT_DUE);
        String amountSource = "AMOUNT_DUE";
        double amountConf = 1.0;

        if (rawAmount.isEmpty()) {
            rawAmount = extractField(text, TOTAL_BALANCE);
            amountSource = "TOTAL_ACCOUNT_BALANCE";
            amountConf = 0.95; // Penalización mínima por desvío a balance
        }

        if (rawAmount.isEmpty()) {
            rawAmount = extractField(text, CURRENT_CHARGES);
            amountSource = "CURRENT_CHARGES";
            amountConf = 0.85; // Penalización táctica por contingencia básica
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

        log.debug("[AUDIT_INTELLIGENCE] Vendor 'MIAMI_DADE_WASD' completed extraction via track: {}", amountSource);

        finalScore = Math.min(finalScore, 100);

        return new ExtractionResult(
                "MIAMI_DADE_WASD",
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