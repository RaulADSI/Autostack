package com.reiter.autostack.infrastructure.worker;

import com.reiter.autostack.core.engine.DeterministicExtractor;
import com.reiter.autostack.core.engine.VendorExtractionRegistry;
import com.reiter.autostack.core.engine.VendorDetector;
import com.reiter.autostack.core.model.*;
import com.reiter.autostack.core.repository.InvoiceRepository;
import com.reiter.autostack.core.repository.InvoiceRepository.ClaimedJob;
import com.reiter.autostack.intelligence.IntelligencePlane;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class CognitiveWorker {
    private static final Logger log = LoggerFactory.getLogger(CognitiveWorker.class);
    private static final double ESTIMATED_GEMINI_CALL_COST_USD = 0.0035;

    private static final long MAX_TEXT_SIZE_BYTES = 15 * 1024 * 1024; // 15 MB máximos
    private static final long INFERENCE_TIMEOUT_MINUTES = 10;

    private final InvoiceRepository repository;
    private final VendorDetector vendorDetector;
    private final VendorExtractionRegistry extractorRegistry;
    private final IntelligencePlane intelligencePlane;
    private final MeterRegistry metrics;

    private final ScheduledExecutorService heartbeatExecutor;
    private final ExecutorService asyncInferencePool;

    private final int minimumAcceptedScore;
    private final int batchSize;
    private final String workerId;

    public CognitiveWorker(
            InvoiceRepository repository,
            VendorDetector vendorDetector,
            VendorExtractionRegistry extractorRegistry,
            IntelligencePlane intelligencePlane,
            MeterRegistry metrics,
            ScheduledExecutorService heartbeatExecutor,
            @Value("${autostack.ai.min-score:60}") int minimumAcceptedScore,
            @Value("${autostack.worker.batch-size:5}") int batchSize) {
        this.repository = repository;
        this.vendorDetector = vendorDetector;
        this.extractorRegistry = extractorRegistry;
        this.intelligencePlane = intelligencePlane;
        this.metrics = metrics;
        this.heartbeatExecutor = heartbeatExecutor;
        this.minimumAcceptedScore = minimumAcceptedScore;
        this.batchSize = batchSize;
        this.workerId = generateWorkerIdentity();

        this.asyncInferencePool = Executors.newFixedThreadPool(batchSize, runnable -> {
            Thread t = new Thread(runnable, "autostack-ai-inference-thread");
            t.setDaemon(true);
            return t;
        });
    }

    @Scheduled(fixedDelayString = "${autostack.worker.cognitive.delay:5000}")
    public void processCognitiveQueue() {
        List<ClaimedJob> workBatch = repository.acquirePendingJobs(this.workerId, this.batchSize);
        if (workBatch.isEmpty()) return;

        for (ClaimedJob job : workBatch) {

            AtomicBoolean leaseLost = new AtomicBoolean(false);

            ScheduledFuture<?> heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
                boolean extended = repository.renewLease(job.sha256(), job.leaseToken());
                if (!extended) {
                    log.error("[LEASE_REVOKED] Critical: Worker identity hijacked or lease expired for SHA256: {}", job.sha256());
                    leaseLost.set(true);
                }
            }, 45, 45, TimeUnit.SECONDS);

            String currentVendor = "UNKNOWN";

            try {
                // =======================================================================================
                // 🚀 ESTRATEGIA DE LOCALIZACIÓN MULTIPLATAFORMA (Slashes vs Backslashes Fix)
                // =======================================================================================
                String sha256 = job.sha256();
                Path textPath = Paths.get("storage", "debug-text", sha256 + "_debug.txt");

                // Fallback 1: Normalización de barras para compatibilidad con Windows
                if (!Files.exists(textPath)) {
                    String normalizedPath = job.storedPath().replace("\\", "/");
                    String replacedPath = normalizedPath.replace("storage/blob/", "storage/debug-text/").replace(".pdf", "_debug.txt");
                    textPath = Paths.get(replacedPath);
                }

                // Fallback 2: Localización directa en la bóveda inmutable junto al PDF original
                if (!Files.exists(textPath)) {
                    textPath = Paths.get(job.storedPath().replace(".pdf", "_debug.txt"));
                }

                // Fallback 3: Extensión corta estándar .txt en la carpeta CAS
                if (!Files.exists(textPath)) {
                    textPath = Paths.get(job.storedPath().replace(".pdf", ".txt"));
                }

                // Validación de seguridad definitiva
                if (!Files.exists(textPath)) {
                    log.error("[CRITICAL_PATH_MISSING] El artefacto de texto para el hash {} no se encuentra en ninguna ubicación del disco.", sha256);
                    throw new IntelligencePlane.PermanentInferenceException("Mirror debug text artifact missing after cross-platform scan.");
                }

                log.info("[COGNITIVE_READ] Cargando exitosamente capa de texto plano desde: {}", textPath.getFileName());
                long fileSize = Files.size(textPath);
                if (fileSize > MAX_TEXT_SIZE_BYTES) {
                    throw new IntelligencePlane.PermanentInferenceException("Payload exceeds density boundaries. Size: " + fileSize + " bytes.");
                }

                String plainText = Files.readString(textPath);

                // Paso 1: Clasificación de Huella
                DetectionResult detection = vendorDetector.detect(plainText);
                currentVendor = detection.vendorCode();

                boolean processedViaFastPath = false;

                if (detection.matched()) {
                    Optional<DeterministicExtractor> extractorOpt = extractorRegistry.getExtractor(currentVendor);
                    if (extractorOpt.isPresent()) {
                        DeterministicExtractor extractor = extractorOpt.get();

                        ExtractionResult rawData = extractor.extract(plainText);
                        ExtractionAssessment assessment = extractor.assess(rawData, plainText);

                        if (assessment.valid() && !assessment.layoutDrift()) {
                            if (leaseLost.get()) {
                                throw new IllegalStateException("Aborting local mutation. Lease lost during deterministic extraction.");
                            }

                            log.info("[FAST_PATH_SUCCESS] Cleared vendor '{}' locally. Cost: $0.00 USD.", currentVendor);
                            metrics.counter("autostack.detector.fallback", "vendor", currentVendor, "reason", assessment.failureReason().name()).increment();
                            metrics.counter("autostack.detector.fallback", "vendor", "UNKNOWN", "reason", detection.reason().name()).increment();

                            repository.enrichInvoiceBySha256(job.sha256(), job.leaseToken(), rawData, InvoiceStatus.NEW);
                            processedViaFastPath = true;
                        } else {
                            metrics.counter("autostack.detector.fallback", "vendor", currentVendor, "reason", assessment.failureReason().name()).increment();
                            if (assessment.layoutDrift()) {
                                log.error("[LAYOUT_DRIFT_DETECTED] Tracked drift for layout '{}'.", currentVendor);
                                metrics.counter("autostack.layout.drift", "vendor", currentVendor).increment();
                            }
                        }
                    } else {
                        metrics.counter("autostack.detector.fallback", "vendor", currentVendor, "reason", "EXTRACTOR_NOT_REGISTERED").increment();
                    }
                } else {
                    metrics.counter("autostack.detector.fallback", "vendor", "UNKNOWN", "reason", detection.reason().name()).increment();
                }

                // Paso 2: Fallback Cognitivo Seguro
                if (!processedViaFastPath) {
                    metrics.counter("autostack.fallback.count", "vendor", currentVendor).increment();
                    log.info("[FALLBACK_ESCALATION] Dispatching future envelope for SHA256: {}", job.sha256());

                    final String textPayload = plainText;

                    CompletableFuture<ExtractionResult> inferenceFuture = CompletableFuture.supplyAsync(
                            () -> intelligencePlane.analyze(textPayload), asyncInferencePool
                    );

                    ExtractionResult aiResult = inferenceFuture.get(INFERENCE_TIMEOUT_MINUTES, TimeUnit.MINUTES);

                    if (leaseLost.get()) {
                        throw new IllegalStateException("Aborting remote mutation. Lease lost during AI processing window.");
                    }

                    InvoiceStatus targetStatus = aiResult.strategyMatchScore() >= minimumAcceptedScore
                            ? InvoiceStatus.NEW
                            : InvoiceStatus.REVIEW_REQUIRED;

                    repository.enrichInvoiceBySha256(job.sha256(), job.leaseToken(), aiResult, targetStatus);
                }

            } catch (ExecutionException e) {
                Throwable rootCause = e.getCause();
                if (rootCause instanceof IntelligencePlane.RetryableInferenceException) {
                    handleRetryableError(job, currentVendor);
                } else {
                    handlePermanentError(job, currentVendor, rootCause);
                }
            } catch (TimeoutException e) {
                log.error("[TIMEOUT_EXCEEDED] AI core failed to respond within {} minutes. Routing to Triage.", INFERENCE_TIMEOUT_MINUTES);
                metrics.counter("autostack.pipeline.error", "vendor", currentVendor, "type", "TIMEOUT").increment();
                repository.updateStatusBySha256(job.sha256(), job.leaseToken(), InvoiceStatus.REVIEW_REQUIRED);

            } catch (IllegalStateException e) {
                log.warn("[MUTATION_BLOCKED] Thread execution bypassed successfully: {}", e.getMessage());
                metrics.counter("autostack.pipeline.error", "vendor", currentVendor, "type", "LEASE_CONCURRENCY_LOST").increment();

            } catch (IntelligencePlane.RetryableInferenceException e) {
                handleRetryableError(job, currentVendor);

            } catch (Exception e) {
                handlePermanentError(job, currentVendor, e);

            } finally {
                heartbeatTask.cancel(false);
            }
        }
    }

    private void handleRetryableError(ClaimedJob job, String vendor) {
        log.warn("[QUEUE_BACKOFF] Transient failure caught for vendor '{}'. Moving back to queue context.", vendor);
        metrics.counter("autostack.pipeline.error", "vendor", vendor, "type", "RETRYABLE").increment();
        repository.updateStatusBySha256(job.sha256(), job.leaseToken(), InvoiceStatus.AI_PROCESSING);
    }

    private void handlePermanentError(ClaimedJob job, String vendor, Throwable t) {
        log.error("[QUEUE_QUARANTINE] Fatal processing anomaly for vendor '{}'. Overriding to human triage.", vendor, t);
        metrics.counter("autostack.pipeline.error", "vendor", vendor, "type", "PERMANENT").increment();
        repository.updateStatusBySha256(job.sha256(), job.leaseToken(), InvoiceStatus.REVIEW_REQUIRED);
    }

    private String generateWorkerIdentity() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "unknown-node-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}