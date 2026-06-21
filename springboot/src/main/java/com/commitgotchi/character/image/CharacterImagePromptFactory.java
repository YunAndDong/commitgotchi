package com.commitgotchi.character.image;

import org.springframework.stereotype.Component;

@Component
public class CharacterImagePromptFactory {

    public String createPrompt(String designKeyword) {
        return """
                Create a canonical v2 2x3 Commitgotchi pixel-art sprite sheet for the design keyword "%s".
                The output must contain exactly six character sprites in two rows and three columns.
                Use uniform cell sizes and even spacing: row 1 is baby form, row 2 is mature form.
                Columns are joy, sad, angry in that order.
                Use one solid magenta background color behind every cell for later removal.
                Do not draw text, numbers, labels, captions, watermarks, UI, panels, cards, frames, or visible grid lines.
                Keep every creature centered in its cell with a consistent silhouette, clean outline, and retro handheld game pixel-art style.
                """.formatted(designKeyword.strip());
    }
}
