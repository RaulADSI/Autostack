package com.reiter.autostack.core.engine.extractors;

import com.reiter.autostack.core.engine.RoutingExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Order(20) // Se ejecuta de forma determinista en el orden del Router
@Component
public class WasteManagementExtractor implements RoutingExtractor {
    private static final Logger log = LoggerFactory.getLogger(WasteManagementExtractor.class);

    // Alínea el código con el token "WM" usado en tu base de datos y mapping.csv
    private static final String VENDOR_CODE = "WM";

    // 🛡️ FIRMA DE PROVEEDOR ROBUSTA: Exige el nombre comercial oficial o su dominio web
    private static final Pattern WM_IDENTIFIER_PATTERN = Pattern.compile(
            "WASTE\\s+MANAGEMENT|wm\\.com",
            Pattern.CASE_INSENSITIVE
    );

    // 🎯 PATRÓN DE CAPTURA FLEXIBLE: Intercepta identificadores numéricos y guiones
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(
            "(?:Customer\\s+ID|Account\\s+(?:No|Number))[:\\s]+([0-9\\- ]{10,20})",
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

            // 🚀 NORMALIZACIÓN ESPECÍFICA: Conserva números y guiones estructurados,
            // dinamitando espacios accidentales del OCR (ej: "28 -50742" -> "28-50742")
            String normalizedAccount = rawAccount.replaceAll("[^0-9\\-]", "");

            // 🛡️ GUARDIA DE SEGURIDAD PERIMETRAL: Las cuentas de WM (con guiones) miden entre 12 y 16 caracteres
            if (normalizedAccount.length() < 10 || normalizedAccount.length() > 16) {
                log.warn("[WM_GUARD] Cuenta candidata '{}' rechazada por violar límites de longitud.", normalizedAccount);
                return Optional.empty();
            }

            log.info("[WM_MATCH] Enrutamiento limpio resuelto: {}", normalizedAccount);
            return Optional.of(normalizedAccount);
        }

        log.warn("[WM_MISS] Se reconoció el documento de Waste Management, pero la cuenta falló las guardias de extracción.");
        return Optional.empty();
    }
}