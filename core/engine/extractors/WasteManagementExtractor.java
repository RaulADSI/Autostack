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

    // 🔧 MEJORA 1: Soporta 'Account #', 'Account Number', 'Customer ID' y evita captura voraz de texto continuo
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(
            "(?:Customer\\s+ID|Account\\s*(?:Number|No|#)?)[\\s:#]+([0-9]{2,3}[\\s\\-][0-9]{5}[\\s\\-][0-9]{5}|[0-9\\-]{10,18})",
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

        // Normalización básica de saltos de línea para evitar romper la lectura de la cuenta
        String normalizedText = text.replace('\r', ' ').replace('\n', ' ');

        Matcher matcher = ACCOUNT_PATTERN.matcher(normalizedText);
        if (matcher.find()) {
            String rawAccount = matcher.group(1).trim();

            // Conserva números y guiones, eliminando los espacios intermedios del OCR
            String normalizedAccount = rawAccount.replaceAll("[^0-9\\-]", "");

            // Formatear si viene solo con dígitos sin guiones (ej: 244903033005 -> 24-49030-33005)
            if (!normalizedAccount.contains("-") && normalizedAccount.length() == 12) {
                normalizedAccount = String.format("%s-%s-%s",
                        normalizedAccount.substring(0, 2),
                        normalizedAccount.substring(2, 7),
                        normalizedAccount.substring(7, 12));
            }

            // Validar límites de longitud para formato formateado XX-XXXXX-XXXXX (14 chars)
            if (normalizedAccount.length() < 10 || normalizedAccount.length() > 16) {
                log.warn("[WM_GUARD] Cuenta candidata '{}' rechazada por violar límites de longitud.", normalizedAccount);
                return Optional.empty();
            }

            log.info("[WM_MATCH] Enrutamiento limpio resuelto para WM: {}", normalizedAccount);
            return Optional.of(normalizedAccount);
        }

        log.warn("[WM_MISS] Se reconoció el documento de WM, pero falló la extracción de cuenta.");
        return Optional.empty();
    }
}