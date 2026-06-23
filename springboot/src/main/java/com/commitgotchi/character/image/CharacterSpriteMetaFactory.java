package com.commitgotchi.character.image;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class CharacterSpriteMetaFactory {

    private final ObjectMapper objectMapper;

    public CharacterSpriteMetaFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String fallbackSpriteMetaJson() {
        try {
            return objectMapper.writeValueAsString(fallbackSpriteMeta());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize fallback sprite metadata.", exception);
        }
    }

    public ObjectNode fallbackSpriteMeta() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("columns", 3);
        root.put("rows", 1);

        ObjectNode frameMap = objectMapper.createObjectNode();
        frameMap.set("joy", coordinates(0, 0));
        frameMap.set("sad", coordinates(0, 1));
        frameMap.set("angry", coordinates(0, 2));
        root.set("frameMap", frameMap);

        root.put("transparent", true);
        return root;
    }

    private ArrayNode coordinates(int row, int column) {
        ArrayNode coordinates = objectMapper.createArrayNode();
        coordinates.add(row);
        coordinates.add(column);
        return coordinates;
    }
}
