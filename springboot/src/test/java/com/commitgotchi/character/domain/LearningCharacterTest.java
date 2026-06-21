package com.commitgotchi.character.domain;

import com.commitgotchi.user.domain.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearningCharacterTest {

    @Test
    void newCharacterStartsWithDefaultSystemValues() {
        LearningCharacter character = newCharacter();

        assertThat(character.getName()).isEqualTo("Commit Buddy");
        assertThat(character.getDesignKeyword()).isEqualTo("green console mascot");
        assertThat(character.getPersonality()).isEqualTo("Curious and steady");
        assertThat(character.getStatDb()).isZero();
        assertThat(character.getStatAlgorithm()).isZero();
        assertThat(character.getStatCs()).isZero();
        assertThat(character.getStatNetwork()).isZero();
        assertThat(character.getStatFramework()).isZero();
        assertThat(character.getBattlePower()).isZero();
        assertThat(character.getEmotion()).isEqualTo(CharacterEmotion.JOY);
        assertThat(character.getImageStatus()).isEqualTo(CharacterImageStatus.PENDING);
        assertThat(character.getStatusMessage()).isEqualTo("Ready to learn");
        assertThat(character.isEvolved()).isFalse();
        assertThat(character.isActive()).isFalse();
        assertThat(character.getCreatedAt()).isNotNull();
        assertThat(character.getUpdatedAt()).isNotNull();
    }

    @Test
    void userEditableMethodsOnlyChangeUserEditableFields() {
        LearningCharacter character = newCharacter();

        character.rename("Query Sage");
        character.changeDesignKeyword("blue query wizard");
        character.changePersonality("Patient database mentor");

        assertThat(character.getName()).isEqualTo("Query Sage");
        assertThat(character.getDesignKeyword()).isEqualTo("blue query wizard");
        assertThat(character.getPersonality()).isEqualTo("Patient database mentor");
        assertThat(character.getStatDb()).isZero();
        assertThat(character.getStatAlgorithm()).isZero();
        assertThat(character.getStatCs()).isZero();
        assertThat(character.getStatNetwork()).isZero();
        assertThat(character.getStatFramework()).isZero();
        assertThat(character.getBattlePower()).isZero();
        assertThat(character.getEmotion()).isEqualTo(CharacterEmotion.JOY);
        assertThat(character.isEvolved()).isFalse();
    }

    @Test
    void scoreDeltaCannotMakeStatsNegativeAndKeepsBattlePowerConsistent() {
        LearningCharacter character = newCharacter();

        character.applyScoreDelta(2, 3, 5, 7, 11);

        assertThat(character.getStatDb()).isEqualTo(2);
        assertThat(character.getStatAlgorithm()).isEqualTo(3);
        assertThat(character.getStatCs()).isEqualTo(5);
        assertThat(character.getStatNetwork()).isEqualTo(7);
        assertThat(character.getStatFramework()).isEqualTo(11);
        assertThat(character.getBattlePower()).isEqualTo(28);
        assertThatThrownBy(() -> character.applyScoreDelta(-3, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stat");
        assertThat(character.getStatDb()).isEqualTo(2);
        assertThat(character.getBattlePower()).isEqualTo(28);
    }

    @Test
    void scoreDeltaCannotOverflowStatsOrBattlePower() {
        LearningCharacter character = newCharacter();

        character.applyScoreDelta(Integer.MAX_VALUE, 0, 0, 0, 0);

        assertThatThrownBy(() -> character.applyScoreDelta(0, 1, 0, 0, 0))
                .isInstanceOf(ArithmeticException.class);
        assertThat(character.getStatAlgorithm()).isZero();
        assertThat(character.getBattlePower()).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> character.applyScoreDelta(1, 0, 0, 0, 0))
                .isInstanceOf(ArithmeticException.class);
        assertThat(character.getStatDb()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void characterEvolvesOnlyOnceWhenBattlePowerReachesThreshold() {
        LearningCharacter character = newCharacter();

        character.applyScoreDelta(250, 250, 250, 249, 0);

        assertThat(character.getBattlePower()).isEqualTo(999);
        assertThat(character.isEvolved()).isFalse();

        character.applyScoreDelta(1, 0, 0, 0, 0);

        assertThat(character.getBattlePower()).isEqualTo(1000);
        assertThat(character.isEvolved()).isTrue();

        character.applyScoreDelta(0, 0, 0, 0, 1);

        assertThat(character.getBattlePower()).isEqualTo(1001);
        assertThat(character.isEvolved()).isTrue();
    }

    @Test
    void systemImageMethodsOwnImageStatusAndSpriteFields() {
        LearningCharacter character = newCharacter();

        character.markReady("https://example.com/sprite.png", "{\"frames\":4}");

        assertThat(character.getImageStatus()).isEqualTo(CharacterImageStatus.READY);
        assertThat(character.getSpriteSheetUrl()).isEqualTo("https://example.com/sprite.png");
        assertThat(character.getSpriteMeta()).isEqualTo("{\"frames\":4}");

        character.markFallback("https://example.com/fallback.png", "{\"fallback\":true}");

        assertThat(character.getImageStatus()).isEqualTo(CharacterImageStatus.FALLBACK);
        assertThat(character.getSpriteSheetUrl()).isEqualTo("https://example.com/fallback.png");
        assertThat(character.getSpriteMeta()).isEqualTo("{\"fallback\":true}");

        character.markFailed();

        assertThat(character.getImageStatus()).isEqualTo(CharacterImageStatus.FAILED);
        assertThat(character.getSpriteSheetUrl()).isNull();
        assertThat(character.getSpriteMeta()).isNull();
    }

    private LearningCharacter newCharacter() {
        User user = User.create("character-owner@example.com", "$2a$12$hash");
        return LearningCharacter.create(
                user,
                "Commit Buddy",
                "green console mascot",
                "Curious and steady"
        );
    }
}
