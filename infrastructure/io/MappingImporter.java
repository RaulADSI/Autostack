package com.reiter.autostack.infrastructure.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.Set;

@Component
public class MappingImporter {
    private static final Logger log = LoggerFactory.getLogger(MappingImporter.class);

    @Value("${autostack.datasource.url:jdbc:sqlite:storage/autostack.db}")
    private String dbUrl;

    private final Path csvPath = Paths.get("storage/mapping.csv");

    @EventListener(ApplicationReadyEvent.class)
    public void syncCsvToDatabase() {
        if (!Files.exists(csvPath)) {
            log.warn("[IMPORTER_WARN] No se encontró el archivo 'storage/mapping.csv'. Omitiendo sincronización relacional.");
            return;
        }

        String sql = """
            INSERT OR REPLACE INTO vendor_property_mapping 
            (vendor_code, vendor_account, property_code, appfolio_email, updated_at) 
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP);
        """;

        // Set de control para evitar duplicados humanos en el archivo físico mapping.csv
        Set<String> uniqueMappingsCheck = new HashSet<>();

        try (BufferedReader reader = Files.newBufferedReader(csvPath);
             Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            String line;
            int count = 0;
            boolean isHeader = true;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (isHeader) {
                    isHeader = false; // Saltamos la fila de títulos del CSV
                    continue;
                }

                String[] tokens = line.split(",");
                if (tokens.length < 4) {
                    log.warn("[IMPORTER_SKIP] Línea malformada detectada en el CSV: {}", line);
                    continue;
                }

                // Limpiamos espacios accidentales que puedan venir en el Excel/CSV
                String vendorCode = tokens[0].trim();
                String vendorAccount = tokens[1].trim();
                String propertyCode = tokens[2].trim();
                String appfolioEmail = tokens[3].trim();

                // 🔑 Generamos la llave relacional compuesta única
                String compositeKey = vendorCode + "::" + vendorAccount;

                // 🛑 CONTROL DE UNICIDAD: Si la combinación Proveedor::Cuenta ya existe en el set, saltamos la línea
                if (uniqueMappingsCheck.contains(compositeKey)) {
                    log.error("[IMPORTER_DUPLICATE_ERROR] Línea omitida en 'mapping.csv'. El proveedor '{}' ya tiene asignada la cuenta '{}'. " +
                                    "Corrige el archivo para evitar conflictos con la propiedad '{}'.",
                            vendorCode, vendorAccount, propertyCode);
                    continue;
                }

                // Registramos la combinación en el set si es nueva
                uniqueMappingsCheck.add(compositeKey);

                pstmt.setString(1, vendorCode);
                pstmt.setString(2, vendorAccount);
                pstmt.setString(3, propertyCode);
                pstmt.setString(4, appfolioEmail);
                pstmt.addBatch();
                count++;
            }

            if (count > 0) {
                pstmt.executeBatch();
                log.info("[IMPORTER_SUCCESS] Sincronización exitosa. {} mapeos relacionales cargados desde 'mapping.csv' hacia SQLite.", count);
            }

        } catch (Exception e) {
            log.error("[IMPORTER_CRASH] Falló la sincronización del catálogo 'mapping.csv' con la base de datos", e);
        }
    }
}