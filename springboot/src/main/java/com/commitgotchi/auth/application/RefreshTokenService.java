package com.commitgotchi.auth.application;

import com.commitgotchi.auth.api.dto.TokenPairResponse;
import com.commitgotchi.auth.domain.RefreshToken;
import com.commitgotchi.auth.domain.RefreshTokenRepository;
import com.commitgotchi.common.error.InvalidRefreshTokenException;
import com.commitgotchi.common.error.ReusedRefreshTokenException;
import com.commitgotchi.security.IssuedAccessToken;
import com.commitgotchi.security.JwtTokenProvider;
import com.commitgotchi.user.domain.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_LENGTH = 43;
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenReuseRevocationService reuseRevocationService;
    private final SecureRandom secureRandom;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenReuseRevocationService reuseRevocationService,
            Clock clock
    ) {
        this(refreshTokenRepository, jwtTokenProvider, reuseRevocationService, new SecureRandom(), clock);
    }

    RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenReuseRevocationService reuseRevocationService,
            SecureRandom secureRandom,
            Clock clock
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.reuseRevocationService = reuseRevocationService;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    public IssuedRefreshToken issue(User user) {
        IssuedRefreshToken issued = generate();
        refreshTokenRepository.save(RefreshToken.issue(
                user,
                hash(issued.rawToken()),
                issued.expiresAt(),
                issued.expiresAt().minus(REFRESH_TOKEN_TTL)
        ));
        return issued;
    }

    @Transactional
    public TokenPairResponse rotate(String rawToken) {
        if (!isValidFormat(rawToken)) {
            throw new InvalidRefreshTokenException();
        }

        RefreshToken current = refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        if (current.isRevoked()) {
            reuseRevocationService.revokeAllActive(current.getUser().getId());
            throw new ReusedRefreshTokenException();
        }
        Instant now = clock.instant();
        if (current.isExpiredAt(now)) {
            throw new InvalidRefreshTokenException();
        }

        // 잠금 조회가 join fetch로 소유 사용자를 같은 트랜잭션 내 현재 DB 상태로 로딩하고,
        // FK ON DELETE CASCADE가 토큰 존재 시 사용자 존재를 보장하므로 그대로 사용한다.
        User user = current.getUser();
        current.revoke(now);
        IssuedRefreshToken refreshToken = issue(user);
        IssuedAccessToken accessToken = jwtTokenProvider.issue(user.getId(), user.getEmail(), user.getRole());
        return TokenPairResponse.from(accessToken, refreshToken);
    }

    @Transactional
    public void revokeIfPresent(String rawToken) {
        if (!isValidFormat(rawToken)) {
            return;
        }
        refreshTokenRepository.deleteActiveByTokenHash(hash(rawToken));
    }

    IssuedRefreshToken generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return new IssuedRefreshToken(
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                clock.instant().plus(REFRESH_TOKEN_TTL)
        );
    }

    String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean isValidFormat(String rawToken) {
        // 32바이트 → Base64url no-padding은 항상 정확히 43자이고, 이 charset의 43자는
        // 모두 32바이트로 디코딩되므로 길이·charset 검사만으로 형식 검증이 충분하다.
        return rawToken != null
                && rawToken.length() == TOKEN_LENGTH
                && rawToken.matches("[A-Za-z0-9_-]+");
    }
}
