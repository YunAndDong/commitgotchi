package com.commitgotchi.character.application;

import com.commitgotchi.character.api.dto.CharacterCreateRequest;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.user.domain.User;
import com.commitgotchi.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CharacterCreationService {

    private static final int MAX_CHARACTERS_PER_USER = 3;

    private final UserRepository userRepository;
    private final LearningCharacterRepository characterRepository;

    public CharacterCreationService(
            UserRepository userRepository,
            LearningCharacterRepository characterRepository
    ) {
        this.userRepository = userRepository;
        this.characterRepository = characterRepository;
    }

    @Transactional
    public LearningCharacter create(long userId, CharacterCreateRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user does not exist."));
        List<LearningCharacter> existingCharacters =
                characterRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (existingCharacters.size() >= MAX_CHARACTERS_PER_USER) {
            throw new CharacterLimitExceededException();
        }

        boolean deactivated = false;
        for (LearningCharacter existing : existingCharacters) {
            if (existing.isActive()) {
                existing.deactivate();
                deactivated = true;
            }
        }
        if (deactivated) {
            characterRepository.flush();
        }

        LearningCharacter character = LearningCharacter.create(
                user,
                request.name(),
                request.keyword(),
                request.personality()
        );
        character.activate();
        return characterRepository.saveAndFlush(character);
    }
}
