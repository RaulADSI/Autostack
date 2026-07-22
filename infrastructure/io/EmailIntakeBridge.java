package com.reiter.autostack.infrastructure.io;

import jakarta.mail.*;
import jakarta.mail.search.FlagTerm;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Properties;
import java.util.UUID;

@Service
public class EmailIntakeBridge {

    private static final Logger log = LoggerFactory.getLogger(EmailIntakeBridge.class);

    private final LinkPdfDownloader pdfDownloader;

    @Value("${autostack.mail.imap.host:imap.gmail.com}")
    private String imapHost;

    @Value("${autostack.mail.imap.port:993}")
    private String imapPort;

    @Value("${autostack.mail.imap.username}")
    private String username;

    @Value("${autostack.mail.imap.password}")
    private String password;

    public EmailIntakeBridge(LinkPdfDownloader pdfDownloader) {
        this.pdfDownloader = pdfDownloader;
    }

    /**
     * Polling cada 10 minutos para revisar correos no leídos
     */
    @Scheduled(fixedDelayString = "${autostack.mail.imap.poll-interval-ms:600000}")
    public void pollIncomingEmails() {
        log.debug("[EMAIL_BRIDGE_POLL] Iniciando escaneo de buzón IMAP...");

        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imaps");
        properties.put("mail.imaps.host", imapHost);
        properties.put("mail.imaps.port", imapPort);
        properties.put("mail.imaps.ssl.enable", "true");

        try {
            Session session = Session.getDefaultInstance(properties);
            Store store = session.getStore("imaps");
            store.connect(imapHost, username, password);

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            // Buscar mensajes no leídos
            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));

            for (Message message : messages) {
                String subject = message.getSubject() != null ? message.getSubject() : "";
                String from = message.getFrom() != null && message.getFrom().length > 0 ? message.getFrom()[0].toString() : "";

                // Filtro para Waste Management
                if (subject.toLowerCase().contains("waste management") || subject.toLowerCase().contains("your invoice") || from.contains("wm.com")) {
                    log.info("[EMAIL_MATCH] Notificación de factura detectada de WM: '{}'", subject);

                    String htmlContent = getTextFromMessage(message);
                    String pdfLink = extractPdfUrlFromHtml(htmlContent);

                    if (pdfLink != null) {
                        String generatedName = "wm_link_" + UUID.randomUUID().toString().substring(0, 8) + ".pdf";
                        pdfDownloader.downloadPdfFromLink(pdfLink, generatedName);

                        // Marcar correo como leído para no reprocesar
                        message.setFlag(Flags.Flag.SEEN, true);
                    }
                }
            }

            inbox.close(false);
            store.close();

        } catch (Exception e) {
            log.error("[EMAIL_BRIDGE_ERROR] Error en ciclo de escaneo IMAP: {}", e.getMessage());
        }
    }

    private String extractPdfUrlFromHtml(String html) {
        if (html == null || html.isBlank()) return null;

        Document doc = Jsoup.parse(html);

        // Buscar enlaces que contengan palabras clave de descarga o dominios de WM
        for (Element link : doc.select("a[href]")) {
            String href = link.attr("href");
            String text = link.text().toLowerCase();

            if (text.contains("view invoice") || text.contains("download") || text.contains("ver factura") || href.contains("wm.com")) {
                return href;
            }
        }
        return null;
    }

    private String getTextFromMessage(Message message) throws Exception {
        if (message.isMimeType("text/plain")) {
            return message.getContent().toString();
        } else if (message.isMimeType("text/html")) {
            return message.getContent().toString();
        } else if (message.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) message.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                if (bodyPart.isMimeType("text/html")) {
                    return bodyPart.getContent().toString();
                }
            }
        }
        return "";
    }
}