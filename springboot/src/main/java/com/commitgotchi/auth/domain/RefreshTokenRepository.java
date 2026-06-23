package com.commitgotchi.auth.domain;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RefreshTokenRepository {

    private final RefreshTokenMapper mapper;

    public RefreshTokenRepository(RefreshTokenMapper mapper) {
        this.mapper = mapper;
    }

    public RefreshToken save(RefreshToken token) {
        if (mapper.existsById(token.getId())) {
            mapper.update(token);
        } else {
            mapper.insert(token);
        }
        return mapper.findById(token.getId());
    }

    public Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash) {
        return Optional.ofNullable(mapper.findByTokenHashForUpdate(tokenHash));
    }

    public int revokeAllActiveByUserId(long userId, Instant revokedAt) {
        return mapper.revokeAllActiveByUserId(userId, revokedAt);
    }

    public int deleteActiveByTokenHash(String tokenHash) {
        return mapper.deleteActiveByTokenHash(tokenHash);
    }

    public long countByUserIdAndRevokedAtIsNull(long userId) {
        return mapper.countByUserIdAndRevokedAtIsNull(userId);
    }

    public void deleteAll() {
        mapper.deleteAll();
    }
}
