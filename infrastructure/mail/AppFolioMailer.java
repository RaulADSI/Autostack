package com.reiter.autostack.infrastructure.mail;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class AppFolioMailer {
    private static final Logger log = LoggerFactory.getLogger(AppFolioMailer.class);
    private final JavaMailSender mailSender;

    // Inyecta de forma dinámica el correo corporativo configurado en tus properties
    @Value("${spring.mail.username}")
    private String fromEmail;

    public AppFolioMailer(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendInvoice(String targetEmail, String originalFilename, String physicalCasPath) throws Exception {
        File pdfFile = new File(physicalCasPath);
        if (!pdfFile.exists()) {
            throw new java.io.FileNotFoundException("Physical file disappeared from CAS bunker: " + physicalCasPath);
        }

        // GUARDIA DE EXTENSIÓN: AppFolio requiere estrictamente la extensión .pdf para procesar
        String safeFilename = originalFilename;
        if (!safeFilename.toLowerCase().endsWith(".pdf")) {
            safeFilename += ".pdf";
        }

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        // Obliga al árbol MIME a firmar con tu dominio corporativo autenticado
        helper.setFrom(fromEmail);
        helper.setTo(targetEmail);
        helper.setSubject("AutoStack Smart Sync Token - " + safeFilename);

        // 1. Primero se asocian los recursos binarios al árbol MIME
        FileSystemResource asset = new FileSystemResource(pdfFile);
        helper.addAttachment(safeFilename, asset);

        // 2. Al final se escribe el texto para asegurar la correcta estructura Multipart
        helper.setText("Transmision automatizada de documento financiero.");

        mailSender.send(message);
        log.info("[SMTP_DISPATCH_SUCCESS] Attached '{}' and routed to AppFolio pipeline.", safeFilename);
    }
}