package com.commitgotchi.character.application;

import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.character.image.CharacterImagePresignService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class CharacterGameProjectionService {

    private final LearningCharacterRepository characterRepository;
    private final ObjectMapper objectMapper;
    private final CharacterImagePresignService presignService;

    public CharacterGameProjectionService(
            LearningCharacterRepository characterRepository,
            ObjectMapper objectMapper,
            CharacterImagePresignService presignService
    ) {
        this.characterRepository = characterRepository;
        this.objectMapper = objectMapper;
        this.presignService = presignService;
    }

    @Transactional(readOnly = true)
    public ArrayNode projectCharacters(long userId) {
        ArrayNode characters = objectMapper.createArrayNode();
        characterRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
                .forEach(character -> characters.add(project(character)));
        return characters;
    }

    public ObjectNode project(LearningCharacter character) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", character.getId());
        node.put("name", character.getName());
        node.put("keyword", character.getDesignKeyword());
        node.put("personality", character.getPersonality());
        node.set("stats", stats(character));
        node.put("battlePower", character.getBattlePower());
        node.put("emotion", character.getEmotion().name().toLowerCase(Locale.ROOT));
        node.put("isEvolved", character.isEvolved());
        node.put("imageStatus", character.getImageStatus().name());
        if (character.getSpriteSheetUrl() == null) {
            node.set("spriteSheetUrl", NullNode.instance);
        } else {
            // Stored value may be an s3:// key; mint a fresh presigned GET URL at
            // read time. Non-S3 values (bundled fallback sprite) pass through.
            node.put("spriteSheetUrl", presignService.toDisplayUrl(character.getSpriteSheetUrl()));
        }
        node.set("spriteMeta", spriteMeta(character));
        node.put("active", character.isActive());
        node.put("message", character.getStatusMessage());
        node.put("createdAt", character.getCreatedAt().toString());
        return node;
    }

    private JsonNode spriteMeta(LearningCharacter character) {
        if (character.getSpriteMeta() == null || character.getSpriteMeta().isBlank()) {
            return NullNode.instance;
        }
        try {
            return objectMapper.readTree(character.getSpriteMeta());
        } catch (JsonProcessingException exception) {
            return NullNode.instance;
        }
    }

    private ObjectNode stats(LearningCharacter character) {
        ObjectNode stats = objectMapper.createObjectNode();
        stats.put("algo", character.getStatAlgorithm());
        stats.put("cs", character.getStatCs());
        stats.put("db", character.getStatDb());
        stats.put("net", character.getStatNetwork());
        stats.put("fw", character.getStatFramework());
        return stats;
    }
}
