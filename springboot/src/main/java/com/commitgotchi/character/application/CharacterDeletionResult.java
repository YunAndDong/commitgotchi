package com.commitgotchi.character.application;

import com.commitgotchi.character.domain.LearningCharacter;

import java.util.Optional;

public record CharacterDeletionResult(
        LearningCharacter removed,
        Optional<LearningCharacter> newActive
) {
    public CharacterDeletionResult(LearningCharacter removed, LearningCharacter newActive) {
        this(removed, Optional.ofNullable(newActive));
    }
}
