package com.reiter.autostack.infrastructure.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
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
            // Bypass de Hostname Verification para proxies/VPNs
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");

            // Bypass de certificados SSL para ExpressVPN / Firewalls / SendGrid
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(20));

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
                Path targetDirectory = Path.of(intakeDir);
                if (!Files.exists(targetDirectory)) {
                    Files.createDirectories(targetDirectory);
                }

                Path targetPath = targetDirectory.resolve(preferredFilename);

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