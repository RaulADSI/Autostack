package com.reiter.autostack.infrastructure.mail;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Properties;

@Service
public class AppFolioMailer {
    private static final Logger log = LoggerFactory.getLogger(AppFolioMailer.class);

    // Inyectamos las credenciales de ambos entornos corporativos
    @Value("${autostack.mail.reiter.username}")
    private String reiterUser;
    @Value("${autostack.mail.reiter.password}")
    private String reiterPass;

    @Value("${autostack.mail.homenow.username}")
    private String homenowUser;
    @Value("${autostack.mail.homenow.password}")
    private String homenowPass;

    public void sendInvoice(String targetEmail, String originalFilename, String physicalCasPath, String senderKey) throws Exception {
        File pdfFile = new File(physicalCasPath);
        if (!pdfFile.exists()) {
            throw new java.io.FileNotFoundException("Physical file disappeared from CAS bunker: " + physicalCasPath);
        }

        // SELECCIÓN DINÁMICA DE CREDENCIALES
        String activeUser;
        String activePass;

        if ("homenow".equalsIgnoreCase(senderKey)) {
            activeUser = homenowUser;
            activePass = homenowPass;
        } else {
            // Por defecto, resolvemos con la identidad de Reiter
            activeUser = reiterUser;
            activePass = reiterPass;
        }

        // CONFIGURACIÓN DEL EMISOR SMTP EN CALIENTE (DIFERENTE LOGIN POR CLIENTE)
        JavaMailSenderImpl dynamicMailSender = new JavaMailSenderImpl();
        dynamicMailSender.setHost("smtp.gmail.com");
        dynamicMailSender.setPort(587);
        dynamicMailSender.setUsername(activeUser);
        dynamicMailSender.setPassword(activePass);

        Properties props = dynamicMailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");

        // GUARDIA DE EXTENSIÓN
        String safeFilename = originalFilename.toLowerCase().endsWith(".pdf") ? originalFilename : originalFilename + ".pdf";

        MimeMessage message = dynamicMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        // AISLAMIENTO DE IDENTIDAD IMPLETABLE
        helper.setFrom(activeUser);
        helper.setTo(targetEmail);
        helper.setSubject("AutoStack Smart Sync Token - " + safeFilename);

        FileSystemResource asset = new FileSystemResource(pdfFile);
        helper.addAttachment(safeFilename, asset);
        helper.setText("Transmision automatizada de documento financiero.");

        // Despacho final usando el canal autenticado del cliente específico
        dynamicMailSender.send(message);
        log.info("[SMTP_DISPATCH_SUCCESS] Invoice '{}' routed to AppFolio using tenant sender: <{}>", safeFilename, activeUser);
    }
}