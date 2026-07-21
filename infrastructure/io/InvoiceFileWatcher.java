package com.reiter.autostack.infrastructure.io;

import com.reiter.autostack.core.model.ExtractionResult;
import com.reiter.autostack.core.model.InvoiceStatus;
import com.reiter.autostack.core.repository.InvoiceRepository;
import com.reiter.autostack.intelligence.ExtractorRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Service
public class InvoiceFileWatcher {
    private static final Logger log = LoggerFactory.getLogger(InvoiceFileWatcher.class);

    private final InvoiceRepository repository;
    private final FileIngestionService ingestionService;
    private final PdfTextExtractionService textExtractionService;
    private final ExtractorRouter extractorRouter;

    private final Path intakeDir = Paths.get("storage/intake");

    public InvoiceFileWatcher(InvoiceRepository repository,
                              FileIngestionService ingestionService,
                              PdfTextExtractionService textExtractionService,
                              ExtractorRouter extractorRouter) {
        this.repository = repository;
        this.ingestionService = ingestionService;
        this.textExtractionService = textExtractionService;
        this.extractorRouter = extractorRouter;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startNativeFileWatcher() {
        Thread watcherThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                Files.createDirectories(intakeDir);
                intakeDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

                log.info("[CONTROL_PLANE] Native event-driven WatchService actively listening on: {}", intakeDir.toAbsolutePath());

                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watchService.take();

                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

                        Path fileName = (Path) event.context();
                        Path fullPath = intakeDir.resolve(fileName);

                        if (Files.exists(fullPath) && !Files.isDirectory(fullPath)) {
                            processIncomingFile(fullPath);
                        }
                    }
                    key.reset();
                }
            } catch (IOException e) {
                log.error("[WATCHER_CRITICAL] Storage IO failure: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[WATCHER_SHUTDOWN] File watcher thread interrupted.");
            }
        });

        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void processIncomingFile(Path path) {
        String filename = path.getFileName().toString();
        log.info("[EVENT_TRIGGERED] New file write detected in intake: {}", filename);

        if (!ingestionService.verifyFileSanity(path)) {
            log.warn("[METRIC:QUARANTINE_TOTAL] Isolation triggered for hazardous file: {}", filename);
            ingestionService.isolateToQuarantine(path);
            return;
        }

        try {
            String sha256 = calculateSHA256(path);

            if (repository.isDuplicate(sha256)) {
                log.warn("[DEDUPE_SHIELD] Byte redundancy blocked for '{}'. Vaporizing file.", filename);
                Files.deleteIfExists(path);
                return;
            }

            String dirPart1 = sha256.substring(0, 2);
            String dirPart2 = sha256.substring(2, 4);
            Path casDirectory = Paths.get("storage/blob", dirPart1, dirPart2);
            Files.createDirectories(casDirectory);

            Path casFinalPath = casDirectory.resolve(sha256 + ".pdf");

            // 🚀 Mover a CAS con resiliencia de I/O
            moveFileToCasResilient(path, casFinalPath);
            log.info("[CAS_STORED] Document committed to immutable vault: {}", casFinalPath);

            String extractedText = textExtractionService.extractDocumentText(casFinalPath);
            String textToProcess = (extractedText != null) ? extractedText : "";

            // =======================================================================================
            // 🚀 ESCRITURA UNCONDICIONAL DE ARTEFACTOS (Sincronización forzada para la cola de la IA)
            // =======================================================================================
            try {
                Path debugDir = Paths.get("storage/debug-text");
                Files.createDirectories(debugDir);

                // Generamos los espejos de texto plano en todas las rutas que escanea el CognitiveWorker
                Files.writeString(debugDir.resolve(sha256 + "_debug.txt"), textToProcess);
                Files.writeString(Paths.get("storage", sha256 + "_debug.txt"), textToProcess);
                Files.writeString(Paths.get(sha256 + "_debug.txt"), textToProcess);
                Files.writeString(casDirectory.resolve(sha256 + "_debug.txt"), textToProcess);
                Files.writeString(casDirectory.resolve(sha256 + ".txt"), textToProcess);

                log.info("[DIAGNOSTIC_DUMP] Extracted plaintext mirror synchronized across all cognitive and CAS scopes.");
            } catch (IOException e) {
                log.error("[DIAGNOSTIC_CRASH] Failed to dump verification artifact: {}", e.getMessage());
            }

            // El log de previsualización en consola solo se gatilla si de verdad hay caracteres legibles
            if (!textToProcess.isBlank()) {
                String preview = textToProcess.replaceAll("\\s+", " ");
                preview = preview.substring(0, Math.min(250, preview.length()));
                log.info("[TEXT_PREVIEW] {}", preview);
            } else {
                log.warn("[INTELLIGENCE_WARN] Unreadable text layers (Blank or non-OCR image). Empty companion artifact generated successfully.");
            }

            // Ejecutar la Compulsa de la Matriz de Triage
            ExtractorRouter.TriageDecision decision = extractorRouter.routeAndProcess(textToProcess);
            ExtractorRouter.RoutingMatch match = decision.match();

            com.reiter.autostack.core.model.ExtractionResult legacyResult = (match != null)
                    ? new com.reiter.autostack.core.model.ExtractionResult(
                    match.vendorCode(),
                    new com.reiter.autostack.core.model.FieldConfidence<>(match.routingKey(), 1.0),
                    null, // invoiceNumber
                    null, // invoiceDate
                    null, // amount
                    100)  // strategyMatchScore
                    : new com.reiter.autostack.core.model.ExtractionResult("UNKNOWN", null, null, null, null, 0);

            // Auditoría Estructural de la Primera Fase de Extracción
            printExtractionAudit(filename, legacyResult);

            // Resolviendo el buzón de destino mediante el mapa relacional del CSV
            String targetAppFolioEmail = "UNKNOWN_BUZON";
            String targetSenderKey = "reiter"; // Fallback inicial
            boolean routeFound = false;

            if (match != null && match.routingKey() != null) {
                Optional<InvoiceRepository.PropertyRoute> routeOpt = repository.resolveRoute(
                        match.vendorCode(),
                        match.routingKey()
                );

                if (routeOpt.isPresent()) {
                    targetAppFolioEmail = routeOpt.get().appfolioEmail();
                    targetSenderKey = routeOpt.get().senderEmailKey(); // 👈 Guarda 'homenow'
                    routeFound = true;
                }
            }

            // Orquestación Atómica de Carriles mediante la API del Repositorio
            // Orquestación Atómica de Carriles mediante la API del Repositorio
            switch (decision.track()) {
                case DETERMINISTIC -> {
                    if (!routeFound) {
                        log.warn("[PIPELINE_TRACK] Fast-Track revocado por falta de destino. Forzando triaje manual.");
                        repository.registerNewInvoice(sha256, filename, casFinalPath.toString(), "UNKNOWN_BUZON", targetSenderKey, InvoiceStatus.REVIEW_REQUIRED, "DETERMINISTIC_MISSING_ROUTE", legacyResult);
                    } else {
                        log.info("[PIPELINE_TRACK] Route cleared: FAST_TRACK determinista aprobado.");
                        // 🚀 Pasamos targetSenderKey ('homenow' o 'reiter') para persistirlo en SQLite desde el nacimiento
                        repository.registerNewInvoice(sha256, filename, casFinalPath.toString(), targetAppFolioEmail, targetSenderKey, InvoiceStatus.NEW, "DETERMINISTIC", legacyResult);
                    }
                }

                case HUMAN_AUDIT -> {
                    repository.registerNewInvoice(sha256, filename, casFinalPath.toString(), targetAppFolioEmail, targetSenderKey, InvoiceStatus.REVIEW_REQUIRED, "DETERMINISTIC_AUDIT", legacyResult);
                }

                case INTELLIGENCE_AI -> {
                    repository.registerNewInvoice(sha256, filename, casFinalPath.toString(), targetAppFolioEmail, targetSenderKey, InvoiceStatus.AI_PROCESSING, "FALLBACK_AI", legacyResult);
                }
            }

        } catch (Exception e) {
            log.error("[INGESTION_CRASH] Critical failure handling ingestion pipeline for '{}': {}", filename, e.getMessage());
        }
    }

    /**
     * 🛡️ Mueve un archivo a la boveda CAS garantizando tolerancia a bloqueos temporales de Windows.
     */
    private void moveFileToCasResilient(Path source, Path target) throws IOException {
        for (int i = 0; i < 5; i++) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                if (i == 4) {
                    // Si el salto atómico falla tras 5 intentos, realizamos copia + borrado seguro
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(source);
                    return;
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void printExtractionAudit(String filename, ExtractionResult extraction) {
        String acctValue = (extraction.accountNumber() != null) ? extraction.accountNumber().value() : "N/A";
        double acctConf = (extraction.accountNumber() != null) ? extraction.accountNumber().confidence() : 0.0;
        double amntValue = (extraction.amount() != null) ? extraction.amount().value() : 0.0;
        double amntConf = (extraction.amount() != null) ? extraction.amount().confidence() : 0.0;

        log.info("""
        
        ======================= [EXTRACTION_AUDIT] =======================
        FILE       : {}
        VENDOR     : {}
        ACCOUNT    : {} (Confidence: {})
        AMOUNT     : ${} (Confidence: {})
        MATCH SCORE: {}/100
        ==================================================================
        """,
                filename, extraction.vendor(),
                acctValue, acctConf,
                amntValue, amntConf,
                extraction.strategyMatchScore()
        );
    }

    private String calculateSHA256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}