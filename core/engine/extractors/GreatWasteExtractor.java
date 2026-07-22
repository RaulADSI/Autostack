package com.reiter.autostack.core.engine.extractors;

import com.reiter.autostack.core.model.ExtractionResult;
import com.reiter.autostack.core.model.FieldConfidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Order(50)
@Component
public class GreatWasteExtractor {

    private static final Logger log = LoggerFactory.getLogger(GreatWasteExtractor.class);

    // Identificador único del proveedor en tu matriz de mapeo
    public static final String VENDOR_CODE = "GREATWASTE";

    // PATRONES REGEX DEDICADOS

    // Vendor Signatures
    private static final Pattern VENDOR_PATTERN = Pattern.compile(
            "(?i)Great\\s+Waste\\s+Service",
            Pattern.CASE_INSENSITIVE
    );

    // Account Number: "Account Number 9002070" o "ActNbr: 9002070"
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(
            "(?i)(?:Account\\s+Number|ActNbr:?)\\s*(\\d{6,10})"
    );

    // Invoice Number: "9002070 642745 Invoice Date" (Extrae el id tras la cuenta en la cabecera)
    private static final Pattern INVOICE_NUMBER_PATTERN = Pattern.compile(
            "(?i)Account\\s+Number\\s+\\d+\\s+(\\d{5,8})\\s+Invoice\\s+Date"
    );

    // Invoice Date: "INVOICE 7/16/26"
    private static final Pattern INVOICE_DATE_PATTERN = Pattern.compile(
            "(?i)INVOICE\\s+(\\d{1,2}/\\d{1,2}/\\d{2,4})"
    );

    // Amount: "$531.80" o "Invoice Total: $531.80"
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?i)Invoice\\s+Total:?\\s*\\$?([0-9]{1,3}(?:,[0-9]{3})*\\.[0-9]{2})|\\$([0-9]{1,3}(?:,[0-9]{3})*\\.[0-9]{2})\\s+Account\\s+Summary"
    );

    /**
     * Comprueba si el texto procesado pertenece a Great Waste Services
     */
    public boolean isMatch(String text) {
        return text != null && VENDOR_PATTERN.matcher(text).find();
    }

    /**
     * Procesa y estructura la extracción determinista
     */
    public ExtractionResult extract(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new ExtractionResult(VENDOR_CODE, null, null, null, null, 0);
        }

        String accountNumber = extractRegex(ACCOUNT_PATTERN, rawText);
        String invoiceNumber = extractRegex(INVOICE_NUMBER_PATTERN, rawText);
        String invoiceDate = extractRegex(INVOICE_DATE_PATTERN, rawText);

        Double amount = null;
        String rawAmount = extractRegex(AMOUNT_PATTERN, rawText);
        if (rawAmount != null) {
            try {
                amount = Double.parseDouble(rawAmount.replace(",", ""));
            } catch (NumberFormatException e) {
                log.warn("[GREATWASTE_EXTRACTOR] Error al parsear el monto: {}", rawAmount);
            }
        }

        // Asignación de Confianza por Campo
        FieldConfidence<String> acctConf = (accountNumber != null) ? new FieldConfidence<>(accountNumber, 1.0) : null;
        FieldConfidence<String> invNumConf = (invoiceNumber != null) ? new FieldConfidence<>(invoiceNumber, 1.0) : null;
        FieldConfidence<String> invDateConf = (invoiceDate != null) ? new FieldConfidence<>(invoiceDate, 1.0) : null;
        FieldConfidence<Double> amountConf = (amount != null) ? new FieldConfidence<>(amount, 1.0) : null;

        // Score determinista perfecto (100) si se extrajo cuenta y monto
        int matchScore = (accountNumber != null && amount != null) ? 100 : 70;

        return new ExtractionResult(
                VENDOR_CODE,
                acctConf,
                invNumConf,
                invDateConf,
                amountConf,
                matchScore
        );
    }

    private String extractRegex(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            // Recorremos los grupos de captura por si la regex tiene alternancias
            for (int i = 1; i <= matcher.groupCount(); i++) {
                if (matcher.group(i) != null) {
                    return matcher.group(i).trim();
                }
            }
        }
        return null;
    }
}