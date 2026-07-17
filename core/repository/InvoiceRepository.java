package com.reiter.autostack.core.repository;

import com.reiter.autostack.core.model.ExtractionResult;
import com.reiter.autostack.core.model.InvoiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InvoiceRepository {
    private static final Logger log = LoggerFactory.getLogger(InvoiceRepository.class);

    private final String dbUrl;
    private static final long LEASE_TIMEOUT_MS = 15 * 60 * 1000; // 15 minutos de tolerancia

    public InvoiceRepository(@Value("${autostack.datasource.url}") String dbUrl) {
        this.dbUrl = dbUrl;
        ensureStorageStructureExists();
        verifySqliteVersion();
        initializeAndMigrateLedgerSchema();
    }

    public record ClaimedJob(String sha256, String storedPath, String leaseToken) {}
    public record PropertyRoute(String propertyCode, String appfolioEmail) {}

    private void verifySqliteVersion() {
        String sql = "SELECT sqlite_version();";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                String version = rs.getString(1);
                log.info("[LEDGER_ENGINE] Native SQLite Core Version Handshake: {}", version);

                String[] parts = version.split("\\.");
                int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
                int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

                if (major < 3 || (major == 3 && minor < 35)) {
                    throw new IllegalStateException("AutoStack V3 requires SQLite 3.35.0+ to execute atomic RETURNING statements.");
                }
            }
        } catch (SQLException | NumberFormatException e) {
            throw new RuntimeException("Handshake negotiation failed with database native layer", e);
        }
    }

    /**
     * 🧱 ADQUISICIÓN ATÓMICA V3: Estructura cíclica limpia con rollback explícito y asertivo
     */
    public List<ClaimedJob> acquirePendingJobs(String workerId, int batchSize) {
        List<ClaimedJob> claimedJobs = new ArrayList<>();
        long currentTimestamp = System.currentTimeMillis();
        long expirationThreshold = currentTimestamp - LEASE_TIMEOUT_MS;

        String uniqueLeaseToken = workerId + ":" + UUID.randomUUID().toString().substring(0, 8);

        String evictSql = """
            UPDATE invoices 
            SET status = 'AI_PROCESSING', leased_at = NULL, leased_by = NULL, lease_recovery_count = lease_recovery_count + 1
            WHERE status = 'PROCESSING' AND leased_at < ? AND retry_count < 3;
        """;

        String poisonPillSql = """
            UPDATE invoices
            SET status = 'REVIEW_REQUIRED', leased_at = NULL, leased_by = NULL, error_message = 'LEASE_TIMEOUT_EXCEEDED'
            WHERE status = 'PROCESSING' AND leased_at < ? AND retry_count >= 3;
        """;

        String claimSql = """
            UPDATE invoices
            SET status = 'PROCESSING', leased_at = ?, leased_by = ?
            WHERE id IN (
                SELECT id FROM invoices
                WHERE status = 'AI_PROCESSING'
                ORDER BY id ASC
                LIMIT ?
            ) AND status = 'AI_PROCESSING'
            RETURNING sha256, stored_path;
        """;

        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement transactionStmt = conn.createStatement()) {

            transactionStmt.execute("PRAGMA busy_timeout = 5000;");
            conn.setAutoCommit(true);

            transactionStmt.execute("BEGIN IMMEDIATE");

            try {
                try (PreparedStatement evictPstmt = conn.prepareStatement(evictSql);
                     PreparedStatement poisonPstmt = conn.prepareStatement(poisonPillSql)) {
                    evictPstmt.setLong(1, expirationThreshold);
                    int evicted = evictPstmt.executeUpdate();

                    poisonPstmt.setLong(1, expirationThreshold);
                    int poisoned = poisonPstmt.executeUpdate();

                    if (evicted > 0 || poisoned > 0) {
                        log.warn("[QUEUE_VACUUM] Operational reset completed. Restored: {} | Quarantined to Triage: {}", evicted, poisoned);
                    }
                }

                try (PreparedStatement claimPstmt = conn.prepareStatement(claimSql)) {
                    claimPstmt.setLong(1, currentTimestamp);
                    claimPstmt.setString(2, uniqueLeaseToken);
                    claimPstmt.setInt(3, batchSize);

                    try (ResultSet rs = claimPstmt.executeQuery()) {
                        while (rs.next()) {
                            claimedJobs.add(new ClaimedJob(
                                    rs.getString("sha256"),
                                    rs.getString("stored_path"),
                                    uniqueLeaseToken
                            ));
                        }
                    }
                }

                transactionStmt.execute("COMMIT");

            } catch (Exception transactionError) {
                try {
                    log.warn("[LEDGER_ABORT] Intent collapsed inside batch execution. Triggering native ROLLBACK.");
                    transactionStmt.execute("ROLLBACK");
                } catch (SQLException rollbackError) {
                    log.error("[LEDGER_ROLLBACK_FAIL] Critical failure executing emergency native rollback context", rollbackError);
                }
                throw transactionError;
            }
        } catch (Exception e) {
            log.error("[LEDGER_CONCURRENCY_FAIL] Work acquisition pipeline blocked or failed", e);
            claimedJobs.clear();
        }
        return claimedJobs;
    }

    public boolean renewLease(String sha256, String leaseToken) {
        String sql = """
            UPDATE invoices 
            SET leased_at = ? 
            WHERE sha256 = ? AND leased_by = ? AND status = 'PROCESSING'
        """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, System.currentTimeMillis());
            pstmt.setString(2, sha256);
            pstmt.setString(3, leaseToken);

            int affected = pstmt.executeUpdate();
            if (affected == 1) {
                log.debug("[LEASES_HEARTBEAT] Lease token extended successfully for SHA256: {}", sha256);
                return true;
            }
            log.warn("[LEASES_HEARTBEAT_REJECTED] Heartbeat pulse denied. Node lost lease token ownership for SHA256: {}", sha256);
            return false;
        } catch (SQLException e) {
            log.error("[LEASES_HEARTBEAT_CRASH] Failed to execute lease extension for token '{}'", leaseToken, e);
            return false;
        }
    }

    public void enrichInvoiceBySha256(String sha256, String leaseToken, ExtractionResult extraction, InvoiceStatus newStatus) {
        String sql = """
            UPDATE invoices 
            SET status = ?, vendor = ?, account_number = ?, invoice_number = ?, invoice_date = ?, 
                amount = ?, match_score = ?, account_confidence = ?, amount_confidence = ?,
                invoice_confidence = ?, invoice_date_confidence = ?, ai_provider = ?, ai_model = ?, 
                ai_response = ?, ai_processed_at = CURRENT_TIMESTAMP, leased_at = NULL, leased_by = NULL
            WHERE sha256 = ? AND leased_by = ? AND status = 'PROCESSING'
        """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            String cleanVendor = Optional.ofNullable(extraction.vendor())
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .orElse("UNKNOWN_VENDOR");

            String acct = (extraction.accountNumber() != null && extraction.accountNumber().value() != null) ? extraction.accountNumber().value() : "NOT_FOUND";
            String invNum = (extraction.invoiceNumber() != null && extraction.invoiceNumber().value() != null) ? extraction.invoiceNumber().value() : "NOT_FOUND";
            String invDate = (extraction.invoiceDate() != null && extraction.invoiceDate().value() != null) ? extraction.invoiceDate().value() : "NOT_FOUND";
            double amnt = (extraction.amount() != null) ? extraction.amount().value() : 0.0;

            double acctConf = (extraction.accountNumber() != null) ? extraction.accountNumber().confidence() : 0.0;
            double amntConf = (extraction.amount() != null) ? extraction.amount().confidence() : 0.0;
            double invConf = (extraction.invoiceNumber() != null) ? extraction.invoiceNumber().confidence() : 0.0;
            double invDateConf = (extraction.invoiceDate() != null) ? extraction.invoiceDate().confidence() : 0.0;

            pstmt.setString(1, newStatus.name());
            pstmt.setString(2, cleanVendor);
            pstmt.setString(3, acct);
            pstmt.setString(4, invNum);
            pstmt.setString(5, invDate);
            pstmt.setDouble(6, amnt);
            pstmt.setInt(7, extraction.strategyMatchScore());
            pstmt.setDouble(8, acctConf);
            pstmt.setDouble(9, amntConf);
            pstmt.setDouble(10, invConf);
            pstmt.setDouble(11, invDateConf);
            pstmt.setString(12, "AUTOSTACK_INTELLIGENCE");
            pstmt.setString(13, "gemini-2.5-flash");
            pstmt.setString(14, "{\"triage_action\":\"COGNITIVE_ENRICHMENT\",\"calculated_score\":" + extraction.strategyMatchScore() + "}");

            pstmt.setString(15, sha256);
            pstmt.setString(16, leaseToken);

            int rowsAffected = pstmt.executeUpdate();
            if (rowsAffected == 0) {
                log.error("[STALE_LEASE_ABORT] Token verification failed for lease '{}' on SHA256 {}. Write rejected.", leaseToken, sha256);
                throw new IllegalStateException("CAS Ownership verification failed. Job lease expired and was acquired by another node.");
            }

            log.info("[LEDGER_ENRICHED] SHA256 target secured under token '{}'. Released as '{}'.", leaseToken, newStatus.name());
        } catch (SQLException e) {
            log.error("[LEDGER_ENRICH_FAIL] Aborted live update for SHA256 {}: {}", sha256, e.getMessage());
        }
    }

    public void updateStatusBySha256(String sha256, String leaseToken, InvoiceStatus newStatus) {
        String sql;

        if (newStatus == InvoiceStatus.AI_PROCESSING) {
            sql = """
                UPDATE invoices 
                SET status = CASE WHEN retry_count + 1 >= 3 THEN 'REVIEW_REQUIRED' ELSE 'AI_PROCESSING' END, 
                    error_message = CASE WHEN retry_count + 1 >= 3 THEN 'EXCEEDED_MAXIMUM_COGNITIVE_RETRY_ATTEMPTS' ELSE NULL END,
                    retry_count = retry_count + 1,
                    leased_at = NULL,
                    leased_by = NULL
                WHERE sha256 = ? AND leased_by = ? AND status = 'PROCESSING'
            """;
        } else {
            sql = """
                UPDATE invoices 
                SET status = ?, error_message = ?, leased_at = NULL, leased_by = NULL
                WHERE sha256 = ? AND leased_by = ? AND status = 'PROCESSING'
            """;
        }

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            if (newStatus == InvoiceStatus.AI_PROCESSING) {
                pstmt.setString(1, sha256);
                pstmt.setString(2, leaseToken);
            } else {
                pstmt.setString(1, newStatus.name());
                pstmt.setString(2, newStatus == InvoiceStatus.REVIEW_REQUIRED ? "AI_PLANE_PERMANENT_ERROR_TRIGGERED" : null);
                pstmt.setString(3, sha256);
                pstmt.setString(4, leaseToken);
            }

            int rowsAffected = pstmt.executeUpdate();
            if (rowsAffected == 0) {
                log.warn("[STALE_LEASE_IGNORE] Circuit Breaker bypass: Lease token '{}' had already expired for SHA256 {}.", leaseToken, sha256);
            } else {
                log.info("[LEDGER_STATE_MUTATED] Atomic state transformation resolved to '{}' under lease token '{}'.", newStatus.name(), leaseToken);
            }
        } catch (SQLException e) {
            log.error("[LEDGER_STATUS_FAIL] Emergency state update crashed for SHA256 {}: {}", sha256, e.getMessage());
        }
    }

    public void registerNewInvoice(String sha256, String filename, String casPath, String targetEmail, InvoiceStatus initialStatus, String sourceType, ExtractionResult extraction) {
        String sql = """
            INSERT INTO invoices (
                sha256, original_filename, stored_path, appfolio_email, status, source_type, 
                vendor, account_number, invoice_number, invoice_date, amount, match_score, 
                account_confidence, amount_confidence, invoice_confidence, invoice_date_confidence
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            String safeVendor = Optional.ofNullable(extraction.vendor())
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .orElse("UNKNOWN_VENDOR");

            String acct = (extraction.accountNumber() != null && extraction.accountNumber().value() != null) ? extraction.accountNumber().value() : "NOT_FOUND";
            String invNum = (extraction.invoiceNumber() != null && extraction.invoiceNumber().value() != null) ? extraction.invoiceNumber().value() : "NOT_FOUND";
            String invDate = (extraction.invoiceDate() != null && extraction.invoiceDate().value() != null) ? extraction.invoiceDate().value() : "NOT_FOUND";
            double amnt = (extraction.amount() != null) ? extraction.amount().value() : 0.0;

            double acctConf = (extraction.accountNumber() != null) ? extraction.accountNumber().confidence() : 0.0;
            double amntConf = (extraction.amount() != null) ? extraction.amount().confidence() : 0.0;
            double invConf = (extraction.invoiceNumber() != null) ? extraction.invoiceNumber().confidence() : 0.0;
            double invDateConf = (extraction.invoiceDate() != null) ? extraction.invoiceDate().confidence() : 0.0;

            pstmt.setString(1, sha256);
            pstmt.setString(2, filename);
            pstmt.setString(3, casPath);
            pstmt.setString(4, targetEmail);
            pstmt.setString(5, initialStatus.name());
            pstmt.setString(6, sourceType);
            pstmt.setString(7, safeVendor);
            pstmt.setString(8, acct);
            pstmt.setString(9, invNum);
            pstmt.setString(10, invDate);
            pstmt.setDouble(11, amnt);
            pstmt.setInt(12, extraction.strategyMatchScore());
            pstmt.setDouble(13, acctConf);
            pstmt.setDouble(14, amntConf);
            pstmt.setDouble(15, invConf);
            pstmt.setDouble(16, invDateConf);

            pstmt.executeUpdate();
            log.info("[LEDGER_SECURED] Locked status '{}' for filename '{}'.", initialStatus.name(), filename);
        } catch (SQLException e) {
            log.error("[LEDGER_WRITE_FAIL] Database registration aborted: {}", e.getMessage());
        }
    }

    // =======================================================================================
    // 🚀 DISPATCH ENGINE INJECTIONS (Métodos añadidos para solucionar el "cannot find symbol")
    // =======================================================================================

    /**
     * Busca los IDs que están listos en cola para despacho de correo.
     */
    public List<Integer> fetchAvailableInvoiceIds(int limit) {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT id FROM invoices WHERE status = 'NEW' OR (status = 'FAILED' AND retry_count < 5) ORDER BY id ASC LIMIT ?;";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt("id"));
                }
            }
        } catch (SQLException e) {
            log.error("[DISPATCH_ENGINE_FAIL] Failed to poll candidate ids from database", e);
        }
        return ids;
    }

    /**
     * Intenta reclamar una factura de forma atómica cambiando su estado a 'DISPATCHING'.
     * Retorna verdadero si logró ganar la carrera de concurrencia.
     */
    public boolean tryClaimInvoice(int id) {
        String sql = "UPDATE invoices SET status = 'DISPATCHING' WHERE id = ? AND (status = 'NEW' OR status = 'FAILED');";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            return pstmt.executeUpdate() == 1;
        } catch (SQLException e) {
            log.error("[DISPATCH_ENGINE_FAIL] Crash trying to secure lock for record id {}", id, e);
            return false;
        }
    }

    /**
     * Resuelve el veredicto final del SMTP actualizando el registro a SENT o recalculando reintentos.
     */
    public void updateFinalStatus(int id, InvoiceStatus status, String errorMessage) {
        String sql;
        if (status == InvoiceStatus.FAILED) {
            sql = """
                UPDATE invoices 
                SET status = CASE WHEN retry_count + 1 >= 5 THEN 'DEAD' ELSE 'FAILED' END,
                    error_message = ?,
                    retry_count = retry_count + 1
                WHERE id = ?
            """;
        } else {
            sql = "UPDATE invoices SET status = ?, error_message = ?, sent_at = CURRENT_TIMESTAMP WHERE id = ?;";
        }

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            if (status == InvoiceStatus.FAILED) {
                pstmt.setString(1, errorMessage);
                pstmt.setInt(2, id);
            } else {
                pstmt.setString(1, status.name());
                pstmt.setString(2, errorMessage);
                pstmt.setInt(3, id);
            }
            pstmt.executeUpdate();
            log.info("[DISPATCH_MUTATED] Ledger transaction finalized for ID {} with outcome status: {}", id, status.name());
        } catch (SQLException e) {
            log.error("[DISPATCH_MUTATE_CRASH] Broken state progression for ledger ID {}", id, e);
        }
    }

    // =======================================================================================
    // SISTEMA CORE Y ESQUEMAS CONSTANTES
    // =======================================================================================

    private void initializeAndMigrateLedgerSchema() {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {

            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");

            // 1. Tabla principal de facturas
            String createBaseTableSql = """
            CREATE TABLE IF NOT EXISTS invoices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sha256 TEXT NOT NULL UNIQUE,
                original_filename TEXT NOT NULL,
                stored_path TEXT NOT NULL,
                appfolio_email TEXT NOT NULL,
                status TEXT NOT NULL,
                source_type TEXT NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            );
        """;
            stmt.execute(createBaseTableSql);

            // 2. 🚀 NUEVA: Tabla relacional de enrutamiento para el MVP
            String createMappingTableSql = """
            CREATE TABLE IF NOT EXISTS vendor_property_mapping (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                vendor_code TEXT NOT NULL,
                vendor_account TEXT NOT NULL,
                property_code TEXT NOT NULL,
                appfolio_email TEXT NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(vendor_code, vendor_account)
            );
        """;
            stmt.execute(createMappingTableSql);

            // 3. Inyección segura de columnas evolutivas
            safelyAddColumn(conn, "vendor", "TEXT");
            safelyAddColumn(conn, "account_number", "TEXT");
            safelyAddColumn(conn, "invoice_number", "TEXT");
            safelyAddColumn(conn, "invoice_date", "TEXT");
            safelyAddColumn(conn, "amount", "REAL");
            safelyAddColumn(conn, "match_score", "INTEGER DEFAULT 0");
            safelyAddColumn(conn, "account_confidence", "REAL DEFAULT 0.0");
            safelyAddColumn(conn, "amount_confidence", "REAL DEFAULT 0.0");
            safelyAddColumn(conn, "invoice_confidence", "REAL DEFAULT 0.0");
            safelyAddColumn(conn, "invoice_date_confidence", "REAL DEFAULT 0.0");
            safelyAddColumn(conn, "retry_count", "INTEGER DEFAULT 0");
            safelyAddColumn(conn, "sent_at", "DATETIME");
            safelyAddColumn(conn, "error_message", "TEXT");
            safelyAddColumn(conn, "ai_provider", "TEXT");
            safelyAddColumn(conn, "ai_model", "TEXT");
            safelyAddColumn(conn, "ai_response", "TEXT");
            safelyAddColumn(conn, "ai_processed_at", "TEXT");
            safelyAddColumn(conn, "leased_at", "INTEGER");
            safelyAddColumn(conn, "leased_by", "TEXT");
            safelyAddColumn(conn, "lease_recovery_count", "INTEGER DEFAULT 0");

            // 4. Índices de rendimiento óptimo
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_queue_claim ON invoices(status, id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_invoices_status_lease ON invoices(status, leased_at);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_leased_owner ON invoices(leased_by, status);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_invoices_sha256 ON invoices(sha256);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mapping_lookup ON vendor_property_mapping(vendor_code, vendor_account);");

            log.info("[CONTROL_PLANE] Ledger transaction infrastructure verified and operating at maximum performance.");
        } catch (SQLException e) {
            log.error("[LEDGER_CRITICAL_FAIL] Database migration engine crashed", e);
        }
    }

    public boolean isDuplicate(String sha256) {
        String sql = "SELECT 1 FROM invoices WHERE sha256 = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sha256);
            try (ResultSet rs = pstmt.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }

    private void ensureStorageStructureExists() {
        try {
            Files.createDirectories(Paths.get("storage/intake"));
            Files.createDirectories(Paths.get("storage/blob"));
            Files.createDirectories(Paths.get("storage/quarantine"));
            Files.createDirectories(Paths.get("storage/debug-text"));
        } catch (IOException e) {
            log.error("[DATA_PLANE_CRITICAL] Failed to establish physical structures: {}", e.getMessage());
        }
    }

    private void safelyAddColumn(Connection conn, String columnName, String columnType) {
        String checkSql = "PRAGMA table_info(invoices);";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(checkSql)) {
            boolean exists = false;
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                String alterSql = String.format("ALTER TABLE invoices ADD COLUMN %s %s;", columnName, columnType);
                stmt.execute(alterSql);
                log.info("[MIGRATION_ENGINE] Successfully injected column '{}' into table 'invoices'.", columnName);
            }
        } catch (SQLException e) { log.error("[MIGRATION_ERROR] Failed checking column configuration", e); }
    }
    /**
     * Mapea una cuenta de proveedor directamente a su buzón de AppFolio de forma determinista.
     */
    public Optional<PropertyRoute> resolveRoute(String vendorCode, String vendorAccount) {
        String sql = "SELECT property_code, appfolio_email FROM vendor_property_mapping WHERE vendor_code = ? AND vendor_account = ?;";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, vendorCode);
            pstmt.setString(2, vendorAccount);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PropertyRoute(
                            rs.getString("property_code"),
                            rs.getString("appfolio_email")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("[ROUTER_CRASH] Falló la consulta de enrutamiento para {}/{}", vendorCode, vendorAccount, e);
        }
        return Optional.empty();
    }
}