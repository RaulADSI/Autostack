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
    public boolean waitUntilStable(Path path) {
        try {
            long sizeBefore = Files.size(path);
            long sizeAfter = -1;
            int attempts = 0;

            // Comparamos el tamaño del archivo con un intervalo de 800ms
            while (sizeBefore != sizeAfter && attempts < 15) {
                sizeBefore = Files.size(path);
                Thread.sleep(800);
                sizeAfter = Files.size(path);
                attempts++;
            }

            if (attempts >= 15) {
                log.error("[DATA_PLANE_WARN] File stream remained open too long or size is volatile. Aborting: {}", path.getFileName());
                return false;
            }
            return true;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[DATA_PLANE_EXCEPTION] Error checking file stability: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 🔮 VERIFICACIÓN DE FIRMA MÁGICA: Abre el binario en modo lectura pura
     * y extrae los primeros 4 bytes para confirmar que estructuralmente es un %PDF.
     */
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
     * ☣️ AISLAMIENTO OPERACIONAL: Mueve físicamente el binario contaminado
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