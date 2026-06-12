package com.commitgotchi.auth.application;

import com.commitgotchi.auth.domain.RefreshTokenRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-12T10:00:00Z");

    @Test
    void generatesThirtyTwoByteBase64UrlTokenWithoutPaddingAndThirtyDayExpiry() {
        RefreshTokenService service = new RefreshTokenService(
                null,
                null,
                null,
                new SecureRandom(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        IssuedRefreshToken issued = service.generate();

        assertThat(issued.rawToken()).hasSize(43).doesNotContain("=");
        assertThat(issued.rawToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(issued.rawToken())).hasSize(32);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
    }

    @Test
    void hashesRawTokenAsDeterministicLowercaseSha256Hex() throws Exception {
        RefreshTokenService service = new RefreshTokenService(
                null,
                null,
                null,
                new SecureRandom(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        String raw = "a".repeat(43);
        String expected = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))
        );

        assertThat(service.hash(raw)).isEqualTo(expected).matches("[0-9a-f]{64}");
        assertThat(service.hash(raw)).isEqualTo(service.hash(raw));
    }

    @Test
    void logoutSilentlyIgnoresMalformedTokenWithoutCallingRepository() {
        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        RefreshTokenService service = service(repository);

        service.revokeIfPresent("short");

        verify(repository, never()).deleteActiveByTokenHash(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void logoutPassesOnlyHashToRepositoryAndDoesNotSwallowInfrastructureFailure() {
        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        RefreshTokenService service = service(repository);
        String rawToken = "a".repeat(43);
        String tokenHash = service.hash(rawToken);
        when(repository.deleteActiveByTokenHash(tokenHash)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.revokeIfPresent(rawToken))
                .isInstanceOf(IllegalStateException.class);
        verify(repository).deleteActiveByTokenHash(tokenHash);
    }

    private RefreshTokenService service(RefreshTokenRepository repository) {
        return new RefreshTokenService(
                repository,
                null,
                null,
                new SecureRandom(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
