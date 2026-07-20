package com.reiter.autostack.core.engine.extractors;

import com.reiter.autostack.core.engine.RoutingExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Order(20) // Se ejecuta después de Miami-Dade (Order 10)
@Component
public class WasteManagementExtractor implements RoutingExtractor {
    private static final Logger log = LoggerFactory.getLogger(WasteManagementExtractor.class);

    private static final String VENDOR_CODE = "WM";

    // Identificadores del encabezado
    private static final Pattern WM_IDENTIFIER_PATTERN = Pattern.compile(
            "WASTE\\s+MANAGEMENT|wm\\.com",
            Pattern.CASE_INSENSITIVE
    );

    // Rango de 10 a 30 para absorber guiones y espacios accidentales del OCR
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(
            "(?:Customer\\s+ID|Account\\s+(?:No|Number))[:\\s]+([0-9\\- ]{10,30})",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public boolean matches(String text) {
        if (text == null || text.isBlank()) return false;
        return WM_IDENTIFIER_PATTERN.matcher(text).find();
    }

    @Override
    public String vendorCode() {
        return VENDOR_CODE;
    }

    @Override
    public Optional<String> extractRoutingKey(String text) {
        if (text == null || text.isBlank()) return Optional.empty();

        Matcher matcher = ACCOUNT_PATTERN.matcher(text);
        if (matcher.find()) {
            String rawAccount = matcher.group(1).trim();

            // Conserva números y guiones, eliminando los espacios
            String normalizedAccount = rawAccount.replaceAll("[^0-9\\-]", "");

            // Las cuentas de tu CSV (ej. 28-50745-03006) miden exactamente 14 caracteres
            if (normalizedAccount.length() < 10 || normalizedAccount.length() > 16) {
                log.warn("[WM_GUARD] Cuenta candidata '{}' rechazada por violar límites de longitud.", normalizedAccount);
                return Optional.empty();
            }

            log.info("[WM_MATCH] Enrutamiento limpio resuelto: {}", normalizedAccount);
            return Optional.of(normalizedAccount);
        }

        log.warn("[WM_MISS] Se reconoció el documento de WM, pero falló la extracción de cuenta.");
        return Optional.empty();
    }
}