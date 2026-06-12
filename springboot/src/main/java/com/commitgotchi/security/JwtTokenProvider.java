package com.commitgotchi.security;

import com.commitgotchi.user.domain.UserRole;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Component
public class JwtTokenProvider {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtConfiguration configuration;
    private final Clock clock;

    public JwtTokenProvider(
            JwtEncoder encoder,
            JwtDecoder decoder,
            JwtConfiguration configuration,
            Clock clock
    ) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.configuration = configuration;
        this.clock = clock;
    }

    public IssuedAccessToken issue(long userId, String email, UserRole role) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(configuration.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(configuration.issuer())
                .subject(Long.toString(userId))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("email", email)
                .claim("role", role.name())
                .claim("typ", "access")
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
        return new IssuedAccessToken(value, expiresAt);
    }

    public AuthPrincipal validate(String token) {
        final Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidAccessTokenException(exception);
        }

        try {
            if (!"HS256".equals(jwt.getHeaders().get("alg"))
                    || !configuration.issuer().equals(jwt.getClaimAsString("iss"))
                    || !"access".equals(jwt.getClaimAsString("typ"))
                    || !(jwt.getClaims().get("sub") instanceof String)
                    || !(jwt.getClaims().get("email") instanceof String)
                    || !(jwt.getClaims().get("role") instanceof String)
                    || !(jwt.getClaims().get("typ") instanceof String)
                    || jwt.getIssuedAt() == null
                    || jwt.getExpiresAt() == null
                    || jwt.getIssuedAt().isAfter(clock.instant())
                    || (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(clock.instant()))
                    || !Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())
                    .equals(configuration.accessTokenTtl())) {
                throw new InvalidAccessTokenException();
            }

            long userId = Long.parseLong(jwt.getSubject());
            if (userId <= 0) {
                throw new InvalidAccessTokenException();
            }

            String email = jwt.getClaimAsString("email");
            if (email == null
                    || email.isBlank()
                    || email.length() > 254
                    || !email.equals(email.trim().toLowerCase(Locale.ROOT))) {
                throw new InvalidAccessTokenException();
            }

            UserRole role = UserRole.valueOf(jwt.getClaimAsString("role"));
            if (!jwt.getExpiresAt().isAfter(clock.instant())) {
                throw new ExpiredAccessTokenException();
            }
            return new AuthPrincipal(userId, email, role);
        } catch (ExpiredAccessTokenException | InvalidAccessTokenException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidAccessTokenException(exception);
        }
    }
}
