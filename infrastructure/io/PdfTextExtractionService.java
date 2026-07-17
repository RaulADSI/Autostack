package com.reiter.autostack.infrastructure.io;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;

@Service
public class PdfTextExtractionService {
    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractionService.class);

    public String extractDocumentText(Path casPdfPath) {
        long startTime = System.currentTimeMillis();

        try (PDDocument document = Loader.loadPDF(casPdfPath.toFile())) {
            if (document.isEncrypted()) {
                log.warn("[SECURITY_ALERT] PDF at {} is password protected. Extraction blocked.", casPdfPath.getFileName());
                return null;
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1); // Modificar aquí en el futuro si se requiere escanear páginas anexas

            String rawText = stripper.getText(document);

            // Normalización estructural del flujo de texto
            String normalized = rawText.replaceAll("[\\r\\n\\t]+", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            log.info("[METRIC:PDF_PARSE_LATENCY] Extracted text from target scope in {} ms.", (System.currentTimeMillis() - startTime));
            return normalized;

        } catch (IOException e) {
            log.error("[EXTRACTION_CRASH] Failed to parse PDF stream from CAS: {}", e.getMessage());
            return null;
        }
    }
}