package com.reiter.autostack.infrastructure.mail;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Optional;

@Service
public class EmailDispatcher {
    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final JavaMailSender mailSender;
    private final String fromEmail = "accounting@empresa.com"; // 👈 Tu cuenta o alias corporativo

    public EmailDispatcher(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Despacha el PDF adjunto de forma síncrona y retorna el Message-ID oficial de Google.
     */
    public Optional<String> sendInvoiceToSmartBillEntry(String targetBuzon, String filename, String absolutePdfPath) {
        log.info("[SMTP_CONNECT] Iniciando transferencia hacia el buzón: {}", targetBuzon);

        try {
            MimeMessage message = mailSender.createMimeMessage();

            // Usar multipart = true para habilitar el soporte de adjuntos físicos
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(targetBuzon);
            helper.setSubject("AutoStack System Delivery: " + filename);
            helper.setText("Adjunto envío automático de documento comercial para procesamiento en Smart Bill Entry.");

            // Adjuntar el archivo desde el almacenamiento inmutable (CAS)
            File pdfFile = new File(absolutePdfPath);
            if (!pdfFile.exists()) {
                log.error("[SMTP_IO_ERROR] El archivo PDF no existe en la ruta especificada: {}", absolutePdfPath);
                return Optional.empty();
            }

            FileSystemResource fileResource = new FileSystemResource(pdfFile);
            helper.addAttachment(filename, fileResource);

            // 🚀 FASE CRÍTICA: Despacho síncrono bloqueante. El hilo espera la respuesta '250 OK' de Gmail
            mailSender.send(message);

            // 🛡️ Extracción del recibo criptográfico de Google
            String rawMessageId = message.getMessageID();
            String cleanMessageId = (rawMessageId != null) ? rawMessageId.replace("<", "").replace(">", "") : "UNKNOWN_ID";

            log.info("[SMTP_DELIVERED] ¡Confirmación recibida! Correo aceptado por Google. Message-ID: {}", cleanMessageId);
            return Optional.of(cleanMessageId);

        } catch (Exception e) {
            log.error("[SMTP_COLLAPSE] Fallo de transporte en el canal de salida de Google Workspace para '{}'", filename, e);
            return Optional.empty();
        }
    }
}