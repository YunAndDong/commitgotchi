package com.commitgotchi.character.image;

import org.springframework.stereotype.Component;

@Component
public class CharacterImagePromptFactory {

    public String createPrompt(String designKeyword) {
        return """
                Create a transparent PNG 2x3 sprite sheet for a Commitgotchi learning companion.
                Use the character design keyword: "%s".
                Layout exactly 2 rows and 3 columns.
                Row 1 is baby form, row 2 is mature form.
                Columns are joy, sad, angry in that order.
                Keep a consistent cute pixel-art style, clear silhouette, no background, no text.
                """.formatted(designKeyword.strip());
    }
}
