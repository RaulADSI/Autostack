package com.reiter.autostack.infrastructure.mail;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class AppFolioMailer {
    private static final Logger log = LoggerFactory.getLogger(AppFolioMailer.class);
    private final JavaMailSender mailSender;

    // Spring Boot inyecta de forma automatica el JavaMailSender usando el starter-mail
    public AppFolioMailer(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendInvoice(String targetEmail, String originalFilename, String physicalCasPath) throws Exception {
        File pdfFile = new File(physicalCasPath);
        if (!pdfFile.exists()) {
            throw new java.io.FileNotFoundException("Physical file disappeared from CAS bunker: " + physicalCasPath);
        }

        MimeMessage message = mailSender.createMimeMessage();

        // El parametro true habilita el modo Multipart para soportar adjuntos binarios
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setTo(targetEmail);
        helper.setSubject("AutoStack Smart Sync Token - " + originalFilename);
        helper.setText("Transmision automatizada de documento financiero.");

        // Adjuntar el archivo usando abstracciones eficientes de recursos de Spring
        FileSystemResource asset = new FileSystemResource(pdfFile);
        helper.addAttachment(originalFilename, asset);

        mailSender.send(message);
        log.info("[SMTP_DISPATCH_SUCCESS] Attached '{}' and routed to AppFolio pipeline.", originalFilename);
    }
}
