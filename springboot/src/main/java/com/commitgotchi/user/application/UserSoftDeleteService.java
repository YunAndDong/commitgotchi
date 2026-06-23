package com.commitgotchi.user.application;

import com.commitgotchi.auth.domain.RefreshTokenRepository;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class UserSoftDeleteService {

    private final UserRepository userRepository;
    private final LearningCharacterRepository characterRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    public UserSoftDeleteService(
            UserRepository userRepository,
            LearningCharacterRepository characterRepository,
            RefreshTokenRepository refreshTokenRepository,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.characterRepository = characterRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    @Transactional
    public boolean softDelete(long userId) {
        if (userRepository.findByIdForUpdate(userId).isEmpty()) {
            return false;
        }
        Instant deletedAt = clock.instant();
        characterRepository.softDeleteAllByUserId(userId, deletedAt);
        refreshTokenRepository.revokeAllActiveByUserId(userId, deletedAt);
        return userRepository.softDeleteById(userId, deletedAt) > 0;
    }
}
