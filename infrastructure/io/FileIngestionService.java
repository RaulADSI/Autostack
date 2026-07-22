package com.reiter.autostack.infrastructure.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
public class FileIngestionService {
    private static final Logger log = LoggerFactory.getLogger(FileIngestionService.class);
    private static final Path QUARANTINE_DIR = Paths.get("storage/quarantine");

    /**
     * 🔧 HARDENING DE DISCO: Monitorea el archivo hasta que el sistema operativo
     * cierre el canal de escritura. Evita procesar archivos parciales.
     */
    private boolean waitUntilStable(Path path) {
        long previousSize = -1;
        int stableReadings = 0;

        for (int i = 0; i < 15; i++) { // Reintentar durante 3 segundos máximo
            try {
                if (Files.exists(path)) {
                    long currentSize = Files.size(path);
                    if (currentSize > 0 && currentSize == previousSize) {
                        stableReadings++;
                        if (stableReadings >= 2) {
                            return true; //Tamaño estable y no está vacío
                        }
                    } else {
                        stableReadings = 0;
                    }
                    previousSize = currentSize;
                }
            } catch (IOException ignored) {
                // Reintentar en el siguiente ciclo si el archivo sigue bloqueado por Windows
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public boolean verifyFileSanity(Path path) {
        // Primero asegurar que la escritura terminó por completo
        if (!waitUntilStable(path)) return false;

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            if (raf.length() < 1024) { // Descartar basura o archivos vacíos menores a 1KB
                log.warn("[SANITY_FAIL] File '{}' is too small to be a valid financial invoice.", path.getFileName());
                return false;
            }

            raf.seek(0);
            byte[] magicNumber = new byte[4];
            raf.readFully(magicNumber);

            // Estándar hexadecimal para %PDF: 0x25 0x50 0x44 0x46
            boolean isPdf = magicNumber[0] == 0x25 &&
                    magicNumber[1] == 0x50 &&
                    magicNumber[2] == 0x44 &&
                    magicNumber[3] == 0x46;

            if (!isPdf) {
                log.error("[SECURITY_ALERT] File '{}' spoofed its extension but is NOT a valid PDF container.", path.getFileName());
            }
            return isPdf;

        } catch (IOException e) {
            log.error("[SANITY_CRASH] Failed to probe byte stream header for '{}': {}", path.getFileName(), e.getMessage());
            return false;
        }
    }

    /**
     * AISLAMIENTO OPERACIONAL: Mueve físicamente el binario contaminado
     * a la zona de cuarentena para auditoría manual preventiva.
     */
    public void isolateToQuarantine(Path path) {
        String filename = path.getFileName().toString();
        try {
            Files.createDirectories(QUARANTINE_DIR);
            Path targetPath = QUARANTINE_DIR.resolve(System.currentTimeMillis() + "_CORRUPT_" + filename);

            Files.move(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.warn("[QUARANTINE_ISOLATED] File moved to quarantine sandbox: {}", targetPath.getFileName());
        } catch (IOException e) {
            log.error("[QUARANTINE_CRASH] Critical failure! Could not quarantine hazardous file '{}': {}", filename, e.getMessage());
        }
    }
}