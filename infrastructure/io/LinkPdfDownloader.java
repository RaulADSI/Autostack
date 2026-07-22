package com.reiter.autostack.infrastructure.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

@Service
public class LinkPdfDownloader {

    private static final Logger log = LoggerFactory.getLogger(LinkPdfDownloader.class);

    @Value("${autostack.intake.dir:storage/intake}")
    private String intakeDir;

    // Soporte opcional para VPN/Proxy
    @Value("${autostack.proxy.enabled:false}")
    private boolean useProxy;

    @Value("${autostack.proxy.host:127.0.0.1}")
    private String proxyHost;

    @Value("${autostack.proxy.port:8080}")
    private int proxyPort;

    public Path downloadPdfFromLink(String targetUrl, String preferredFilename) {
        try {
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(20));

            // Si WM requiere VPN/Proxy local
            if (useProxy) {
                clientBuilder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
            }

            HttpClient client = clientBuilder.build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .header("Accept", "application/pdf,application/octet-stream,*/*")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                Path targetPath = Path.of(intakeDir, preferredFilename);

                try (InputStream is = response.body()) {
                    Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }

                log.info("[LINK_DOWNLOAD_SUCCESS] PDF extraído desde enlace y depositado en intake: {}", targetPath.getFileName());
                return targetPath;
            } else {
                log.error("[LINK_DOWNLOAD_FAIL] El servidor devolvió código HTTP {}", response.statusCode());
            }

        } catch (Exception e) {
            log.error("[LINK_DOWNLOAD_CRASH] Error al descargar PDF desde enlace: {}", e.getMessage());
        }
        return null;
    }
}