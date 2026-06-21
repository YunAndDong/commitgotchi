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
        root.put("rows", 2);

        ObjectNode frameMap = objectMapper.createObjectNode();
        frameMap.set("baby", frameRow(0));
        frameMap.set("mature", frameRow(1));
        root.set("frameMap", frameMap);

        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("babyPx", 16);
        frame.put("maturePx", 18);
        root.set("frame", frame);
        root.put("transparent", true);
        return root;
    }

    private ObjectNode frameRow(int row) {
        ObjectNode frameRow = objectMapper.createObjectNode();
        frameRow.set("joy", coordinates(row, 0));
        frameRow.set("sad", coordinates(row, 1));
        frameRow.set("angry", coordinates(row, 2));
        return frameRow;
    }

    private ArrayNode coordinates(int row, int column) {
        ArrayNode coordinates = objectMapper.createArrayNode();
        coordinates.add(row);
        coordinates.add(column);
        return coordinates;
    }
}
