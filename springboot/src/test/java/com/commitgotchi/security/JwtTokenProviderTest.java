package com.commitgotchi.security;

import com.commitgotchi.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final Instant NOW = Instant.parse("2026-06-12T10:00:00Z");
    private static final String SECRET = Base64.getEncoder()
            .encodeToString("a".repeat(64).getBytes(StandardCharsets.UTF_8));

    private JwtConfiguration configuration;
    private JwtEncoder encoder;
    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        configuration = new JwtConfiguration(SECRET, "commitgotchi-springboot", Duration.ofMinutes(15));
        encoder = configuration.jwtEncoder();
        provider = new JwtTokenProvider(
                encoder,
                configuration.jwtDecoder(),
                configuration,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void issuesAndValidatesRequiredClaimsForExactlyFifteenMinutes() {
        IssuedAccessToken issued = provider.issue(42L, "person@example.com", UserRole.USER);

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        AuthPrincipal principal = provider.validate(issued.value());
        assertThat(principal).isEqualTo(new AuthPrincipal(42L, "person@example.com", UserRole.USER));

        var jwt = configuration.jwtDecoder().decode(issued.value());
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("person@example.com");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(jwt.getClaimAsString("typ")).isEqualTo("access");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("commitgotchi-springboot");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void distinguishesExpiredTokenFromOtherInvalidContracts() {
        String expired = encode(Map.of(
                "sub", "42",
                "email", "person@example.com",
                "role", "USER",
                "typ", "access"
        ), NOW.minusSeconds(901), NOW.minusSeconds(1), "commitgotchi-springboot");
        String wrongType = encode(Map.of(
                "sub", "42",
                "email", "person@example.com",
                "role", "USER",
                "typ", "refresh"
        ), NOW, NOW.plusSeconds(900), "commitgotchi-springboot");

        assertThatThrownBy(() -> provider.validate(expired))
                .isInstanceOf(ExpiredAccessTokenException.class);
        assertThatThrownBy(() -> provider.validate(wrongType))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsMissingOrInvalidRequiredClaims() {
        for (Map<String, Object> claims : java.util.List.<Map<String, Object>>of(
                Map.of("email", "person@example.com", "role", "USER", "typ", "access"),
                Map.of("sub", "42", "role", "USER", "typ", "access"),
                Map.of("sub", "42", "email", "person@example.com", "typ", "access"),
                Map.of("sub", "42", "email", "person@example.com", "role", "USER"),
                Map.of("sub", "0", "email", "person@example.com", "role", "USER", "typ", "access"),
                Map.of("sub", "42", "email", " Person@Example.COM ", "role", "USER", "typ", "access"),
                Map.of("sub", "42", "email", "person@example.com", "role", "OWNER", "typ", "access")
        )) {
            String token = encode(claims, NOW, NOW.plusSeconds(900), "commitgotchi-springboot");
            assertThatThrownBy(() -> provider.validate(token))
                    .isInstanceOf(InvalidAccessTokenException.class);
        }

        assertThatThrownBy(() -> provider.validate(encode(
                validClaims(), null, NOW.plusSeconds(900), "commitgotchi-springboot", null)))
                .isInstanceOf(InvalidAccessTokenException.class);
        assertThatThrownBy(() -> provider.validate(encode(
                validClaims(), NOW, null, "commitgotchi-springboot", null)))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsMalformedTamperedAndWrongIssuerTokens() {
        IssuedAccessToken issued = provider.issue(42L, "person@example.com", UserRole.USER);
        String tampered = tamperSignature(issued.value());
        String wrongIssuer = encode(Map.of(
                "sub", "42",
                "email", "person@example.com",
                "role", "USER",
                "typ", "access"
        ), NOW, NOW.plusSeconds(900), "other");

        assertThatThrownBy(() -> provider.validate("not-a-jwt"))
                .isInstanceOf(InvalidAccessTokenException.class);
        assertThatThrownBy(() -> provider.validate(tampered))
                .isInstanceOf(InvalidAccessTokenException.class);
        assertThatThrownBy(() -> provider.validate(wrongIssuer))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsFutureIssuedAtAndUnexpectedAlgorithm() {
        String futureIssuedAt = encode(Map.of(
                "sub", "42",
                "email", "person@example.com",
                "role", "USER",
                "typ", "access"
        ), NOW.plusSeconds(1), NOW.plusSeconds(901), "commitgotchi-springboot");
        String hs512 = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS512).type("JWT").build(),
                JwtClaimsSet.builder()
                        .issuer("commitgotchi-springboot")
                        .subject("42")
                        .issuedAt(NOW)
                        .expiresAt(NOW.plusSeconds(900))
                        .claim("email", "person@example.com")
                        .claim("role", "USER")
                        .claim("typ", "access")
                        .build()
        )).getTokenValue();

        assertThatThrownBy(() -> provider.validate(futureIssuedAt))
                .isInstanceOf(InvalidAccessTokenException.class);
        assertThatThrownBy(() -> provider.validate(hs512))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsFutureNotBeforeAndTokensOutsideExactFifteenMinuteLifetime() {
        String futureNotBefore = encode(
                validClaims(), NOW, NOW.plusSeconds(900), "commitgotchi-springboot", NOW.plusSeconds(1));
        String longLived = encode(
                validClaims(), NOW, NOW.plusSeconds(901), "commitgotchi-springboot", null);
        String shortLived = encode(
                validClaims(), NOW, NOW.plusSeconds(899), "commitgotchi-springboot", null);

        assertThatThrownBy(() -> provider.validate(futureNotBefore))
                .isInstanceOf(InvalidAccessTokenException.class);
        assertThatThrownBy(() -> provider.validate(longLived))
                .isInstanceOf(InvalidAccessTokenException.class);
        assertThatThrownBy(() -> provider.validate(shortLived))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    private String encode(
            Map<String, Object> claims,
            Instant issuedAt,
            Instant expiresAt,
            String issuer
    ) {
        return encode(claims, issuedAt, expiresAt, issuer, null);
    }

    private String encode(
            Map<String, Object> claims,
            Instant issuedAt,
            Instant expiresAt,
            String issuer,
            Instant notBefore
    ) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder().issuer(issuer);
        if (issuedAt != null) {
            builder.issuedAt(issuedAt);
        }
        if (expiresAt != null) {
            builder.expiresAt(expiresAt);
        }
        if (notBefore != null) {
            builder.notBefore(notBefore);
        }
        claims.forEach(builder::claim);
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                builder.build()
        )).getTokenValue();
    }

    private Map<String, Object> validClaims() {
        return Map.of(
                "sub", "42",
                "email", "person@example.com",
                "role", "USER",
                "typ", "access"
        );
    }

    private String tamperSignature(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(signatureStart);
        char replacement = first == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
    }
}
