package com.reiter.autostack.infrastructure.mail;

import com.reiter.autostack.core.model.InvoiceStatus;
import com.reiter.autostack.core.repository.InvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

@Service
public class InvoiceDispatcher {
    private static final Logger log = LoggerFactory.getLogger(InvoiceDispatcher.class);
    private final InvoiceRepository repository;
    private final AppFolioMailer mailer;
    private final String dbUrl;

    public InvoiceDispatcher(
            InvoiceRepository repository,
            AppFolioMailer mailer,
            @Value("${autostack.datasource.url}") String dbUrl
    ){
        this.repository = repository;
        this.mailer = mailer;
        this.dbUrl = dbUrl;
    }

    @Scheduled(fixedDelay = 30000)
    public void executeDispatchLoop() {
        List<Integer> candidateIds = repository.fetchAvailableInvoiceIds(10);
        if (candidateIds.isEmpty()) return;

        log.info("[DISPATCH_LOOP] Found {} candidate logs primed for delivery. Negotiating leases...", candidateIds.size());

        for (int id : candidateIds) {
            if (!repository.tryClaimInvoice(id)) continue;

            String sql = """
    SELECT original_filename, 
           appfolio_email, 
           stored_path, 
           retry_count,
           COALESCE(sender_email_key, 'reiter') AS active_tenant_key
    FROM invoices
    WHERE id = ?
""";

            try (Connection conn = DriverManager.getConnection(dbUrl);
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setInt(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        int currentRetries = rs.getInt("retry_count");
                        String originalFilename = rs.getString("original_filename");
                        String targetEmail = rs.getString("appfolio_email");
                        String storedPath = rs.getString("stored_path");
                        String senderKey = rs.getString("active_tenant_key");

                        log.info("[JOB_START] Delivery sequence for ID {} ('{}'). Dispatch track: {}/5, Tenant: '{}'",
                                id, originalFilename, (currentRetries + 1), senderKey);

                        mailer.sendInvoice(targetEmail, originalFilename, storedPath, senderKey);
                        repository.updateFinalStatus(id, InvoiceStatus.SENT, null);
                    }
                }
            } catch (Exception ex) {
                log.error("[SMTP_FAIL] Delivery crashed for record ID {}. Invoking ledger state transition: {}", id, ex.getMessage());
                repository.updateFinalStatus(id, InvoiceStatus.FAILED, ex.getMessage());
            }
        }
    }
}