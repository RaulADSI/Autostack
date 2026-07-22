package com.reiter.autostack.core.engine.extractors;

import com.reiter.autostack.core.engine.RoutingExtractor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Order(40)
@Component
public class GeorgiaPowerExtractor implements RoutingExtractor {

    // Pattern para capturar bloques de 5-5 dígitos (ej. 25819-74345)
    private static final Pattern HYPHEN_ACCOUNT_PATTERN = Pattern.compile("\\b(\\d{5}-\\d{5})\\b");

    // Pattern para capturar números continuos de 10 a 12 dígitos
    private static final Pattern RAW_ACCOUNT_PATTERN = Pattern.compile("\\b(\\d{10,12})\\b");

    @Override
    public String vendorCode() {
        return "GAPOWER";
    }

    @Override
    public boolean matches(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String upper = text.toUpperCase();

        // Firmas fuertes para identificar a Georgia Power en el texto
        return upper.contains("GEORGIA POWER")
                || upper.contains("241 RALPH MCGILL BLVD")
                || (upper.contains("ATLANTA, GA 30308") && upper.contains("ACCOUNT NUMBER"));
    }

    @Override
    public Optional<String> extractRoutingKey(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        // 1. Prioridad: Capturar formato con guión y limpiar a solo números (ej. "25819-74345" -> "2581974345")
        Matcher hyphenMatcher = HYPHEN_ACCOUNT_PATTERN.matcher(text);
        if (hyphenMatcher.find()) {
            String cleanAccount = hyphenMatcher.group(1).replace("-", "").trim();
            return Optional.of(cleanAccount);
        }

        // 2. Secundario: Capturar número corrido continuo
        Matcher rawMatcher = RAW_ACCOUNT_PATTERN.matcher(text);
        if (rawMatcher.find()) {
            String rawAccount = rawMatcher.group(1);
            // Si viene con prefijo '02' de 12 dígitos, recortamos a los 10 dígitos reales de la cuenta
            if (rawAccount.length() == 12 && rawAccount.startsWith("02")) {
                rawAccount = rawAccount.substring(2);
            }
            return Optional.of(rawAccount);
        }

        return Optional.empty();
    }
}