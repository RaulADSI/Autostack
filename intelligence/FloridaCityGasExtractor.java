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
public class FloridaCityGasExtractor implements TemplateExtractor {
    private static final Logger log = LoggerFactory.getLogger(FloridaCityGasExtractor.class);

    // Matriz de firmas institucionales inmutables
    private static final List<String> FCG_SIGNATURES = List.of(
            "FLORIDA CITY GAS",
            "FLORIDACITYGAS.COM",
            "TOTAL CURRENT CHARGES - UTILITY",
            "FLORIDACITYGASREBATES.COM"
    );

    // 🎯 MEJORA 1: Patrones core y fallbacks compilados una sola vez en memoria estática
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("Account\\s+(?:Number|#)\\s*([0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYS_ACCOUNT_ID = Pattern.compile("\\[Sys_Acct_ID=([0-9]+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern INVOICE_NUMBER = Pattern.compile("Invoice\\s+Number:\\s*([0-9]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern BILLING_DATE = Pattern.compile(
            "Billing\\s+Date\\s*:?\\s*([A-Za-z]+\\s+\\d{1,2},?\\s*\\d{4})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SYS_DOC_DATE = Pattern.compile("\\[Sys_DocDate=([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);

    // 🔧 MEJORA 2: Regex blindada contra captura excesiva (restringe saltos sobre otros símbolos $)
    private static final Pattern PAY_THIS_AMOUNT = Pattern.compile(
            "Please\\s+Pay\\s+This\\s+Amount[^$]{0,100}\\$\\s*([0-9,]+\\.\\d{2})",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TOTAL_BALANCE = Pattern.compile("Total\\s+Account\\s+Balance\\s*\\$\\s*([0-9,]+\\.\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYS_BALANCE_FALLBACK = Pattern.compile("\\[Sys_Balance=([0-9,]+\\.\\d{2})\\]", Pattern.CASE_INSENSITIVE);

    @Override
    public int calculateMatchScore(String text) {
        String upperText = text.toUpperCase();
        int signatureHits = 0;

        for (String signature : FCG_SIGNATURES) {
            if (upperText.contains(signature)) signatureHits++;
        }

        return (signatureHits >= 2) ? 30 : 0;
    }

    @Override
    public ExtractionResult extract(String text, int matchScore) {
        if (matchScore < 30) return ExtractionResult.unknown();

        // Normalización y aplanamiento del flujo horizontal
        String normalized = text
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        int finalScore = matchScore;

        // 1. Extraer Cuenta
        String account = extractField(normalized, ACCOUNT_NUMBER);
        if (account.isEmpty()) {
            account = extractField(normalized, SYS_ACCOUNT_ID);
        }
        double accountConf = !account.isEmpty() ? 1.0 : 0.0;
        if (accountConf > 0) finalScore += 25;

        // 2. Extraer Número de Factura Independiente
        String invoice = extractField(normalized, INVOICE_NUMBER);
        double invoiceConf = !invoice.isEmpty() ? 1.0 : 0.0;
        if (invoiceConf > 0) finalScore += 20;

        // 3. Extraer Fecha
        String date = extractField(normalized, BILLING_DATE);
        if (date.isEmpty()) {
            date = extractField(normalized, SYS_DOC_DATE);
        }
        double dateConf = !date.isEmpty() ? 1.0 : 0.0;
        if (dateConf > 0) finalScore += 5;

        // 4. Extraer Monto con Triple Cascada Controlada
        String rawAmount = extractField(normalized, PAY_THIS_AMOUNT);
        String amountSource = "PAY_THIS_AMOUNT_BY_DATE";
        double amountConf = 1.0;

        if (rawAmount.isEmpty()) {
            rawAmount = extractField(normalized, TOTAL_BALANCE);
            amountSource = "TOTAL_ACCOUNT_BALANCE";
            amountConf = 0.95;
        }

        if (rawAmount.isEmpty()) {
            rawAmount = extractField(normalized, SYS_BALANCE_FALLBACK);
            amountSource = "SYS_METADATA_FALLBACK";
            amountConf = 0.90;
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

        log.debug("[AUDIT_INTELLIGENCE] Vendor 'FLORIDA_CITY_GAS' isolated amount via strategy: {} (Value: {})", amountSource, amount);

        finalScore = Math.min(finalScore, 100);

        return new ExtractionResult(
                "FLORIDA_CITY_GAS",
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
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            // 🔧 MEJORA 3: Visibilidad y trazabilidad en fallos de parseo monetario
            log.warn("[MONETARY_PARSE_FAIL] Unable to parse amount token '{}' in Florida City Gas context.", value);
            return 0.0;
        }
    }
}