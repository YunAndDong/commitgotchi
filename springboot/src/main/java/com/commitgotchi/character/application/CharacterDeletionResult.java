package com.commitgotchi.character.application;

import com.commitgotchi.character.domain.LearningCharacter;

import java.util.Optional;

public record CharacterDeletionResult(
        LearningCharacter removed,
        Optional<LearningCharacter> newActive,
        boolean wasActive
) {
    public CharacterDeletionResult(LearningCharacter removed, LearningCharacter newActive) {
        this(removed, Optional.ofNullable(newActive), removed.isActive());
    }

    public CharacterDeletionResult(LearningCharacter removed, LearningCharacter newActive, boolean wasActive) {
        this(removed, Optional.ofNullable(newActive), wasActive);
    }
}
