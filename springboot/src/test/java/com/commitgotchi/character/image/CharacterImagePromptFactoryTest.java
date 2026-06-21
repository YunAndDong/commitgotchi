package com.commitgotchi.character.image;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterImagePromptFactoryTest {

    @Test
    void promptContainsCanonicalV2Guardrails() {
        String prompt = new CharacterImagePromptFactory().createPrompt(" green study slime ");

        assertThat(prompt).contains("green study slime");
        assertThat(prompt).contains("exactly six character sprites");
        assertThat(prompt).contains("two rows and three columns");
        assertThat(prompt).contains("uniform cell sizes");
        assertThat(prompt).contains("solid magenta background");
        assertThat(prompt).contains("Do not draw text, numbers, labels");
        assertThat(prompt).contains("panels, cards");
        assertThat(prompt).contains("visible grid lines");
    }
}
