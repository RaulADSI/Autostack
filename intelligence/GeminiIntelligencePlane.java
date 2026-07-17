package com.reiter.autostack.intelligence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reiter.autostack.core.model.ExtractionResult;
import com.reiter.autostack.core.model.FieldConfidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Primary
public class GeminiIntelligencePlane implements IntelligencePlane {
    private static final Logger log = LoggerFactory.getLogger(GeminiIntelligencePlane.class);

    private static final int MAX_CONTEXT_CHARACTERS = 42000;
    private static final double HEAD_RATIO = 0.70;

    private static final double COST_PER_INPUT_TOKEN = 0.075 / 1_000_000;
    private static final double COST_PER_OUTPUT_TOKEN = 0.30 / 1_000_000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final String apiKey;

    private enum CircuitState { CLOSED, OPEN, HALF_OPEN }
    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);

    //  CORRECCIÓN: Volvemos a AtomicLong para habilitar operaciones aritméticas atómicas
    private final java.util.concurrent.atomic.AtomicLong consecutiveFailures = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong circuitOpenedAt = new java.util.concurrent.atomic.AtomicLong(0);

    private final AtomicBoolean halfOpenProbeToken = new AtomicBoolean(false);

    private final long circuitBreakerThreshold;
    private static final long CIRCUIT_RESET_MS = 300000; // 5 Minutos

    public static class RetryableInferenceException extends RuntimeException {
        public RetryableInferenceException(String message, Throwable cause) { super(message, cause); }
    }

    public static class PermanentInferenceException extends RuntimeException {
        public PermanentInferenceException(String message) { super(message); }
        public PermanentInferenceException(String message, Throwable cause) { super(message, cause); }
    }

    public GeminiIntelligencePlane(
            @Value("${autostack.intelligence.gemini.model:gemini-2.5-flash}") String model,
            @Value("${autostack.intelligence.gemini.api-key}") String apiKey,
            @Value("${autostack.ai.circuit-breaker.threshold:5}") long circuitBreakerThreshold) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(15000);
        // 🧱 CORRECCIÓN #3: Ventana extendida a 120s para procesar esquemas JSON densos en PDFs largos
        requestFactory.setReadTimeout(120000);

        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.objectMapper = new ObjectMapper();
        this.model = model;
        this.apiKey = apiKey;
        this.circuitBreakerThreshold = circuitBreakerThreshold;
    }

    @Override
    public ExtractionResult analyze(String plainText) {
        evaluateCircuitState();

        String targetUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        long startTime = System.nanoTime();

        String safelyPrunedText;
        if (plainText.length() > MAX_CONTEXT_CHARACTERS) {
            int headLength = (int) (MAX_CONTEXT_CHARACTERS * HEAD_RATIO);
            int tailLength = MAX_CONTEXT_CHARACTERS - headLength;
            safelyPrunedText = plainText.substring(0, headLength) +
                    "\n\n[... OMITTED CONTEXT FOR COST & FOOTER GEOMETRY CONTROL ...]\n\n" +
                    plainText.substring(plainText.length() - tailLength);
        } else {
            safelyPrunedText = plainText;
        }

        String systemInstruction = "You are the core extraction engine of AutoStack. Extract invoice metadata into strictly structured JSON.";

        // 🧱 CORRECCIÓN #5: Forzamos la desactivación de razonamiento profundo y fijamos temperature a 0.0 para congelar la creatividad
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", "Document Content:\n" + safelyPrunedText)))),
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))),
                "generationConfig", Map.of(
                        "temperature", 0.0,
                        "responseMimeType", "application/json",
                        "responseSchema", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "vendor", Map.of("type", "STRING"),
                                        "accountNumber", Map.of("type", "STRING"),
                                        "invoiceNumber", Map.of("type", "STRING"),
                                        "invoiceDate", Map.of("type", "STRING"),
                                        "amount", Map.of("type", "NUMBER")
                                ),
                                "required", List.of("vendor", "accountNumber", "invoiceNumber", "invoiceDate", "amount")
                        )
                )
        );

        try {
            String rawResponse = restClient.post()
                    .uri(targetUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-goog-api-key", apiKey)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(rawResponse);

            // Capa de control de Safety Policy
            JsonNode promptFeedback = root.path("promptFeedback");
            if (!promptFeedback.isMissingNode() && promptFeedback.has("blockReason")) {
                throw new PermanentInferenceException("Gemini core blocked content due to safety policy: " + promptFeedback.path("blockReason").asText());
            }

            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.size() == 0) {
                throw new PermanentInferenceException("Gemini API returned an empty candidate tree.");
            }

            JsonNode firstCandidate = candidates.get(0);
            String finishReason = firstCandidate.path("finishReason").asText("UNKNOWN").toUpperCase();
            if (Set.of("SAFETY", "RECITATION").contains(finishReason)) {
                throw new PermanentInferenceException("Gemini inference aborted due to fatal breach: " + finishReason);
            }

            String jsonText = firstCandidate.path("content").path("parts").get(0).path("text").asText();
            if (jsonText == null || jsonText.isBlank()) {
                throw new PermanentInferenceException("Gemini returned an empty text payload inside content parts.");
            }

            // 🧱 CORRECCIÓN #4: Parseo defensivo intermedio usando JsonNode para evadir MismatchedInputException si amount viene como String
            JsonNode modelPayload = objectMapper.readTree(jsonText);

            String rawVendor = modelPayload.path("vendor").asText("UNKNOWN_VENDOR");
            String accountNumber = modelPayload.path("accountNumber").asText("NOT_FOUND");
            String invoiceNumber = modelPayload.path("invoiceNumber").asText("NOT_FOUND");
            String invoiceDate = modelPayload.path("invoiceDate").asText("NOT_FOUND");

            // Extracción segura del total financiero
            JsonNode amountNode = modelPayload.path("amount");
            double sanitizedAmount = 0.0;
            if (amountNode.isNumber()) {
                sanitizedAmount = amountNode.asDouble();
            } else if (amountNode.isTextual()) {
                try {
                    sanitizedAmount = Double.parseDouble(amountNode.asText().replaceAll("[^0-9.]", ""));
                } catch (NumberFormatException ignored) {}
            }
            if (!Double.isFinite(sanitizedAmount) || sanitizedAmount <= 0.0) {
                sanitizedAmount = 0.0;
            }

            boolean businessCriticalFieldsPresent = isValid(invoiceNumber) && isValid(rawVendor) && sanitizedAmount > 0.0;
            int calculatedScore = calculateWeightedScore(rawVendor, accountNumber, invoiceNumber, invoiceDate, sanitizedAmount);
            int finalScore = businessCriticalFieldsPresent ? calculatedScore : 0;
            double mathematicalConfidence = finalScore / 100.0;

            // Normalización cosmética no destructiva: Preservamos la identidad en rawVendor y normalizamos para indexación
            String normalizedVendor = rawVendor.toUpperCase().trim()
                    .replaceAll("[^A-Z0-9& ]+", "") // Conservamos el carácter ampersand '&' y espacios
                    .replaceAll("\\s+", "_");

            JsonNode usageMetadata = root.path("usageMetadata");
            long inputTokens = usageMetadata.path("promptTokenCount").asLong(0);
            long outputTokens = usageMetadata.path("candidatesTokenCount").asLong(0);
            double exactCostUsd = (inputTokens * COST_PER_INPUT_TOKEN) + (outputTokens * COST_PER_OUTPUT_TOKEN);

            long durationMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

            log.info("[COGNITIVE_METRICS] state={} vendor='{}' normalized='{}' score={} cost_usd={} duration_ms={}",
                    state.get(), rawVendor, normalizedVendor, finalScore, String.format("%.6f", exactCostUsd), durationMillis);

            // Recuperación total de la máquina de estados tras el éxito de la prueba
            consecutiveFailures.set(0L);
            circuitOpenedAt.set(0L);
            halfOpenProbeToken.set(false);
            state.set(CircuitState.CLOSED);

            return new ExtractionResult(
                    normalizedVendor, // Pasamos la clave limpia para matchers de negocio
                    new FieldConfidence<>(accountNumber, mathematicalConfidence),
                    new FieldConfidence<>(invoiceNumber, mathematicalConfidence),
                    new FieldConfidence<>(invoiceDate, mathematicalConfidence),
                    new FieldConfidence<>(sanitizedAmount, mathematicalConfidence),
                    finalScore
            );

        } catch (RestClientResponseException e) {
            int statusCode = e.getStatusCode().value();
            handleFailure();
            if (statusCode == 429 || statusCode >= 500) {
                throw new RetryableInferenceException("Transient HTTP gateway failure", e);
            }
            throw new PermanentInferenceException("Permanent REST configuration or auth block", e);

        } catch (JsonProcessingException e) {
            // Estructura rota irreversible
            throw new PermanentInferenceException("Model generated unparseable or corrupted JSON token stream", e);

        } catch (ResourceAccessException e) {
            // 🧱 CORRECCIÓN #1: Interceptamos correctamente el envoltorio de Spring para caídas de sockets y timeouts
            handleFailure();
            log.warn("[COGNITIVE_RETRYABLE_IO] ResourceAccessException captured. Moving record back to pipeline queue.");
            throw new RetryableInferenceException("Transient remote transport disconnection", e);

        } catch (Exception e) {
            handleFailure();
            throw new RetryableInferenceException("Unexpected peripheral collapse inside inference plane", e);
        }
    }

    /**
     * 🛡 Asignación y control de la máquina de estados concurrente
     */
    private void evaluateCircuitState() {
        CircuitState currentState = state.get();
        if (currentState == CircuitState.CLOSED) return;

        if (currentState == CircuitState.OPEN) {
            long currentTimestamp = System.currentTimeMillis();
            if (currentTimestamp - circuitOpenedAt.get() > CIRCUIT_RESET_MS) {
                // Transición lógica síncrona hacia HALF_OPEN
                if (state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    log.warn("[CIRCUIT_BREAKER] Cooldown window expired. Transitioning to HALF_OPEN state.");
                }
            } else {
                throw new RetryableInferenceException("Circuit breaker active. Outbound endpoint muted locally.", null);
            }
        }

        // 🧱 CORRECCIÓN #2: Control de exclusión mutua para el canal HALF_OPEN (Bloquea el Thundering Herd)
        if (state.get() == CircuitState.HALF_OPEN) {
            if (halfOpenProbeToken.compareAndSet(false, true)) {
                log.info("[CIRCUIT_BREAKER_PROBE] Lease secured. Single canary thread allowed to poll the remote service.");
            } else {
                // Todas las demás peticiones simultáneas se rebotan inmediatamente a la cola asíncrona sin saturar la red
                throw new RetryableInferenceException("Circuit breaker in HALF_OPEN trial. Request deferred locally.", null);
            }
        }
    }

    private void handleFailure() {
        halfOpenProbeToken.set(false); // Liberamos el token si la prueba falló
        long totalFailures = consecutiveFailures.incrementAndGet();

        if (state.get() == CircuitState.HALF_OPEN) {
            state.set(CircuitState.OPEN);
            circuitOpenedAt.set(System.currentTimeMillis());
            log.error("[CIRCUIT_BREAKER_RESET] Canary request failed. Circuit snapped back to OPEN for 5 minutes.");
            return;
        }

        if (totalFailures >= circuitBreakerThreshold && state.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
            circuitOpenedAt.set(System.currentTimeMillis());
            log.error("[CIRCUIT_BREAKER_TRIPPED] Threshold exceeded ({}/{}). Outbound link locked.", totalFailures, circuitBreakerThreshold);
        }
    }

    private int calculateWeightedScore(String v, String a, String i, String d, double amt) {
        int score = 0;
        if (isValid(v)) score += 10;
        if (isValid(a)) score += 25;
        if (isValid(i)) score += 25;
        if (isValid(d)) score += 15;
        if (amt > 0.0) score += 25;
        return score;
    }

    private boolean isValid(String field) {
        return field != null && !field.trim().isEmpty() && !field.equalsIgnoreCase("NOT_FOUND");
    }
}