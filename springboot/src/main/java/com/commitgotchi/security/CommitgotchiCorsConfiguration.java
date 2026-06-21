package com.commitgotchi.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

@Configuration
public class CommitgotchiCorsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CommitgotchiCorsConfiguration.class);
    private static final String TRUSTED_CHROME_EXTENSION_ORIGIN =
            "chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn";

    private final List<String> allowedOrigins;

    public CommitgotchiCorsConfiguration(
            @Value("${commitgotchi.cors.allowed-origins:}") String allowedOrigins,
            Environment environment
    ) {
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        this.allowedOrigins = parseAllowedOrigins(allowedOrigins + "," + TRUSTED_CHROME_EXTENSION_ORIGIN, production);
        if (!production && allowedOrigins.isBlank()) {
            log.warn("CORS allowlist is empty (CORS_ALLOWED_ORIGINS unset); "
                    + "all browser requests to /api/** except the trusted Chrome extension will be rejected "
                    + "without CORS headers. "
                    + "Set CORS_ALLOWED_ORIGINS to an explicit origin (e.g. http://localhost:5173) for browser clients.");
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    static List<String> parseAllowedOrigins(String csv, boolean production) {
        List<String> origins = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toList();

        if (production && origins.stream().noneMatch(CommitgotchiCorsConfiguration::isHttpsOrigin)) {
            throw new IllegalArgumentException("Production CORS allowlist must contain an HTTPS origin");
        }
        origins.forEach(origin -> validateOrigin(origin, production));
        return origins;
    }

    private static boolean isHttpsOrigin(String origin) {
        try {
            return "https".equalsIgnoreCase(new URI(origin).getScheme());
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static void validateOrigin(String origin, boolean production) {
        final URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("CORS allowlist contains an invalid origin");
        }

        String scheme = uri.getScheme();

        // Chrome extensions present an opaque origin of the form
        // chrome-extension://<extension-id> (no port, path, query or fragment).
        // The origin is not network-addressable and is treated as a secure context
        // by the browser, so it is permitted in every profile (including prod).
        if ("chrome-extension".equalsIgnoreCase(scheme)) {
            validateExtensionOrigin(origin, uri);
            return;
        }

        boolean webScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        boolean exactOrigin = webScheme
                && uri.getHost() != null
                && uri.getPort() <= 65535
                && uri.getUserInfo() == null
                && (uri.getPath() == null || uri.getPath().isEmpty())
                && uri.getQuery() == null
                && uri.getFragment() == null
                && !origin.endsWith(":")
                && !origin.contains("*");
        boolean safeHttp = !"http".equalsIgnoreCase(scheme)
                || "localhost".equalsIgnoreCase(uri.getHost())
                || "127.0.0.1".equals(uri.getHost());
        if (!exactOrigin || !safeHttp || (production && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("CORS allowlist must contain exact permitted origins");
        }
    }

    private static void validateExtensionOrigin(String origin, URI uri) {
        String host = uri.getHost();
        boolean exactOrigin = host != null
                && host.matches("[a-p]{32}")
                && uri.getPort() == -1
                && uri.getUserInfo() == null
                && (uri.getPath() == null || uri.getPath().isEmpty())
                && uri.getQuery() == null
                && uri.getFragment() == null
                && !origin.endsWith(":")
                && !origin.contains("*");
        if (!exactOrigin) {
            throw new IllegalArgumentException("CORS allowlist must contain exact permitted origins");
        }
    }
}
