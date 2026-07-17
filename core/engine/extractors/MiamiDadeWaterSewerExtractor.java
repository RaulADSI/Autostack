package com.reiter.autostack.core.engine.extractors;

import com.reiter.autostack.core.engine.RoutingExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Order(30) // Se ejecuta de forma determinista después de FPL (10) y WM (20)
@Component
public class MiamiDadeWaterSewerExtractor implements RoutingExtractor {
    private static final Logger log = LoggerFactory.getLogger(MiamiDadeWaterSewerExtractor.class);

    // Código único del proveedor alineado con tus propiedades de base de datos
    private static final String VENDOR_CODE = "MIAMIDADE_WATER_AND_SEWER";

    // 🛡️ FIRMA DE PROVEEDOR ROBUSTA: Captura el identificador del departamento o la marca institucional
    private static final Pattern MD_IDENTIFIER_PATTERN = Pattern.compile(
            "MIAMI-DADE\\s+WATER\\s+AND\\s+SEWER|MIAMI\\s*DADE\\s*WATER",
            Pattern.CASE_INSENSITIVE
    );

    // 🎯 PATRÓN DE CAPTURA FLEXIBLE: Las facturas de Miami-Dade Water usualmente listan la cuenta
    // antes de los números separados por guiones o espacios (ej. "Account Number 00081721124278")
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(
            "(?:Account\\s+(?:Number|No|#))[^0-9]*([0-9\\- ]{10,20})",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public boolean matches(String text) {
        if (text == null || text.isBlank()) return false;
        return MD_IDENTIFIER_PATTERN.matcher(text).find();
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

            // 🚀 Extrae solo los dígitos puros
            String normalizedAccount = rawAccount.replaceAll("[^0-9]", "");

            // 🛠️ Removemos los ceros a la izquierda obligatoriamente
            normalizedAccount = normalizedAccount.replaceFirst("^0+", "");

            // 🔓 BYPASS TEMPORAL DE SEGURIDAD: Pasamos el dato directo al log y al router
            log.info("[MIAMIDADE_MATCH] Llave relacional procesada con éxito: {}", normalizedAccount);
            return Optional.of(normalizedAccount);
        }

        log.warn("[MIAMIDADE_MISS] Se identificó el proveedor, pero no se localizó ningún patrón de cuenta válido.");
        return Optional.empty();
    }
}
