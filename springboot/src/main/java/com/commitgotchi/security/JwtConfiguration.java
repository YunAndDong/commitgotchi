package com.commitgotchi.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

@Configuration
public class JwtConfiguration {

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    private final SecretKey secretKey;
    private final String issuer;
    private final Duration accessTokenTtl;

    public JwtConfiguration(
            @Value("${commitgotchi.jwt.secret-base64}") String secretBase64,
            @Value("${commitgotchi.jwt.issuer}") String issuer,
            @Value("${commitgotchi.jwt.access-token-ttl}") Duration accessTokenTtl
    ) {
        this.secretKey = decodeSecret(secretBase64);
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("JWT issuer must be configured");
        }
        if (!ACCESS_TOKEN_TTL.equals(accessTokenTtl)) {
            throw new IllegalStateException("Access token TTL must be 15 minutes");
        }
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new JwtIssuerValidator(issuer));
        return decoder;
    }

    @Bean
    public Clock jwtClock() {
        return Clock.systemUTC();
    }

    public String issuer() {
        return issuer;
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }

    private SecretKey decodeSecret(String secretBase64) {
        try {
            byte[] decoded = Base64.getDecoder().decode(secretBase64);
            if (decoded.length < 32) {
                throw new IllegalStateException("JWT secret must be at least 256 bits");
            }
            return new SecretKeySpec(decoded, "HmacSHA256");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT secret must be valid Base64", exception);
        }
    }
}
