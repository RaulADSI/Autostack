package com.reiter.autostack.core.engine.extractors;

import com.reiter.autostack.core.engine.RoutingExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Order(10)
@Component
public class FPLExtractor implements RoutingExtractor {
    private static final Logger log = LoggerFactory.getLogger(FPLExtractor.class);

    private static final String VENDOR_CODE = "FPL";

    // 🛡️ FIRMA DE PROVEEDOR ROBUSTA: Filtra ruidos exigiendo el dominio o la marca institucional larga [cite: 1, 2]
    private static final Pattern FPL_IDENTIFIER_PATTERN = Pattern.compile(
            "FLORIDA\\s+POWER\\s*(?:AND|&)\\s*LIGHT|fpl\\.com",
            Pattern.CASE_INSENSITIVE
    );

    // 🎯 PATRÓN DE CAPTURA DE CUENTA: Flexible para capturar el bloque candidato
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(
            "account\\s*(?:number|#)?\\s*[:\\.-]?\\s*([0-9\\- ]{8,20})",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public boolean matches(String text) {
        if (text == null || text.isBlank()) return false;
        return FPL_IDENTIFIER_PATTERN.matcher(text).find();
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

            // NORMALIZACIÓN UNIVERSAL: Eliminación destructiva de basura tipográfica
            String normalizedAccount = rawAccount.replaceAll("[^0-9]", "");

            // CONTROL DE LÍMITES OPERATIVOS: Evita capturas parciales de fechas o montos accidentales
            if (normalizedAccount.length() < 8 || normalizedAccount.length() > 12) {
                log.warn("[FPL_GUARD] Patrón numérico '{}' interceptado, pero violó los límites de longitud ({} dígitos). Rechazado.",
                        normalizedAccount, normalizedAccount.length());
                return Optional.empty();
            }

            log.info("[FPL_MATCH] Llave relacional validada: {}", normalizedAccount);
            return Optional.of(normalizedAccount);
        }

        log.warn("[FPL_MISS] No se localizó ningún patrón de cuenta válido en el documento.");
        return Optional.empty();
    }
}