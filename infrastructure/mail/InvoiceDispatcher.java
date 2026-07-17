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
        // La consulta SQL ya filtra de forma estricta (status='NEW' OR (status='FAILED' AND retry_count < 5))
        List<Integer> candidateIds = repository.fetchAvailableInvoiceIds(10);
        if (candidateIds.isEmpty()) return;

        log.info("[DISPATCH_LOOP] Found {} candidate logs primed for delivery. Negotiating leases...", candidateIds.size());

        for (int id : candidateIds) {
            if (!repository.tryClaimInvoice(id)) continue;

            try (Connection conn = DriverManager.getConnection(dbUrl);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM invoices WHERE id = ?")) {

                pstmt.setInt(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        int currentRetries = rs.getInt("retry_count");
                        String filename = rs.getString("original_filename");
                        String targetEmail = rs.getString("appfolio_email");
                        String storedPath = rs.getString("stored_path");

                        log.info("[JOB_START] Delivery sequence for ID {} ('{}'). Dispatch track: {}/5",
                                id, filename, (currentRetries + 1));

                        mailer.sendInvoice(targetEmail, filename, storedPath);
                        repository.updateFinalStatus(id, InvoiceStatus.SENT, null);
                    }
                }
            } catch (Exception ex) {
                log.error("[SMTP_FAIL] Delivery crashed for record ID {}. Invoking ledger state transition: {}", id, ex.getMessage());
                // El repositorio incrementará de forma interna el contador y decidirá atómicamente si muta a FAILED o a DEAD
                repository.updateFinalStatus(id, InvoiceStatus.FAILED, ex.getMessage());
            }
        }
    }

}
