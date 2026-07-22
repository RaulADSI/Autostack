package com.reiter.autostack.intelligence;

import com.reiter.autostack.core.engine.RoutingExtractor;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ExtractorRouter {
    private static final Logger log = LoggerFactory.getLogger(ExtractorRouter.class);

    // PROTECCIÓN DE RENDIMIENTO: Límite duro de escaneo para firmas (Cabecera de ~1.5 páginas)
    private static final int MAX_HEADER_SCAN_CHARS = 6000;

    private final List<RoutingExtractor> extractors;
    private final MeterRegistry metrics;

    public ExtractorRouter(List<RoutingExtractor> extractors, MeterRegistry metrics) {
        // Nota: Spring ordena la lista automáticamente si decoras los extractores con @Order(X)
        this.extractors = extractors;
        this.metrics = metrics;

        log.info("[CONTROL_PLANE] ExtractorRouter activo. Motores deterministas indexados: {}",
                extractors.stream().map(RoutingExtractor::vendorCode).toList());
    }

    /**
     * Evalúa el documento y resuelve el destino de enrutamiento óptimo.
     */
    public TriageDecision routeAndProcess(String text) {
        if (text == null || text.isBlank()) {
            incrementRouterMetric("ai_fallback", "UNKNOWN", "empty_text");
            return new TriageDecision(Track.INTELLIGENCE_AI, null);
        }

        // OPTIMIZACIÓN ACELERADA: Slicing defensivo para evitar repeticiones innecesarias de Regex
        String headerText = text.substring(0, Math.min(text.length(), MAX_HEADER_SCAN_CHARS));

        for (RoutingExtractor extractor : extractors) {
            // El match rápido corre únicamente sobre el fragmento inicial optimizado
            if (!extractor.matches(headerText)) {
                continue;
            }

            String vendor = extractor.vendorCode();
            Optional<String> keyOpt = extractor.extractRoutingKey(text); // La cuenta se busca en el texto completo por seguridad

            if (keyOpt.isPresent()) {
                String routingKey = keyOpt.get();
                log.info("[ROUTER_MATCH] vendor={} key={}", vendor, routingKey);

                incrementRouterMetric("deterministic", vendor, "success");
                return new TriageDecision(Track.DETERMINISTIC, new RoutingMatch(vendor, routingKey));
            } else {
                // PUNTO 3: Cortocircuito defensivo. Si el proveedor existe pero no hay cuenta,
                // forzamos triaje humano directo. La IA no debe inventar identidades.
                log.warn("[ROUTER_MISMATCH] Proveedor '{}' identificado pero violó las guardias de la cuenta.", vendor);
                incrementRouterMetric("human_audit", vendor, "missing_routing_key");
                return new TriageDecision(Track.HUMAN_AUDIT, new RoutingMatch(vendor, null));
            }
        }

        // PUNTO 4: Documento totalmente desconocido. Escalada limpia al plano cognitivo.
        log.info("[ROUTER_FALLBACK] Ningún patrón determinista coincidió. Elevando a INTELLIGENCE_AI.");
        incrementRouterMetric("ai_fallback", "UNKNOWN", "no_vendor_match");
        return new TriageDecision(Track.INTELLIGENCE_AI, null);
    }

    private void incrementRouterMetric(String track, String vendor, String outcome) {
        metrics.counter("autostack.router.triage",
                "track", track,
                "vendor", vendor,
                "outcome", outcome
        ).increment();
    }

    // =======================================================================================
    // ⚙️ CONTRATOS ARQUITECTÓNICOS LIMPIOS (DTOs de Enrutamiento Puros)
    // =======================================================================================
    public enum Track {
        DETERMINISTIC,
        HUMAN_AUDIT,
        INTELLIGENCE_AI
    }

    public record RoutingMatch(String vendorCode, String routingKey) {}

    public record TriageDecision(Track track, RoutingMatch match) {}
}