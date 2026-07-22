package com.reiter.autostack.core.engine.extractors;

import com.reiter.autostack.core.engine.RoutingExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Order(60)
@Component
public class FloridaCityGasExtractor implements RoutingExtractor {
    private static final Logger log = LoggerFactory.getLogger(FloridaCityGasExtractor.class);

    private static final String VENDOR_CODE = "FLORIDA_CITY_GAS";

    // Matriz de firmas institucionales inmutables
    private static final List<String> FCG_SIGNATURES = List.of(
            "FLORIDA CITY GAS",
            "FLORIDACITYGAS.COM",
            "TOTAL CURRENT CHARGES - UTILITY",
            "FLORIDACITYGASREBATES.COM"
    );

    // Patrones estáticos compilados en memoria
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile(
            "Account\\s+(?:Number|#|Nbr:?|No\\.?)\\s*([0-9]{6,15})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SYS_ACCOUNT_ID = Pattern.compile("\\[Sys_Acct_ID=([0-9]+)\\]", Pattern.CASE_INSENSITIVE);

    @Override
    public String vendorCode() {
        return VENDOR_CODE;
    }

    /**
     * 1. MATCH RÁPIDO (Se ejecuta sobre la cabecera ~6000 chars)
     */
    @Override
    public boolean matches(String headerText) {
        if (headerText == null || headerText.isBlank()) return false;

        String upperText = headerText.toUpperCase();
        int signatureHits = 0;

        for (String signature : FCG_SIGNATURES) {
            if (upperText.contains(signature)) {
                signatureHits++;
            }
        }

        return signatureHits >= 2;
    }

    /**
     * 2. EXTRACCIÓN DE CUENTA (Se ejecuta sobre el texto completo)
     */
    @Override
    public Optional<String> extractRoutingKey(String fullText) {
        if (fullText == null || fullText.isBlank()) return Optional.empty();

        // Normalización y aplanamiento del flujo horizontal
        String normalized = fullText
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        // 1. Intentar por patrón estándar
        String account = extractField(normalized, ACCOUNT_NUMBER);

        // 2. Fallback a metadatos de sistema
        if (account.isEmpty()) {
            account = extractField(normalized, SYS_ACCOUNT_ID);
        }

        if (!account.isEmpty()) {
            log.debug("[ROUTER_ISOLATED] Cuenta extraída para 'FLORIDA_CITY_GAS': {}", account);
            return Optional.of(account);
        }

        return Optional.empty();
    }

    private String extractField(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) return matcher.group(1).trim();
        return "";
    }
}