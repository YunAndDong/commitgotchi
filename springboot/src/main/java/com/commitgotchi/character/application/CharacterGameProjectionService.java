package com.commitgotchi.character.application;

import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.character.domain.CharacterImageStatus;
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
        if (character.getCatalogCharacterId() == null) {
            node.set("catalogCharacterId", NullNode.instance);
        } else {
            node.put("catalogCharacterId", character.getCatalogCharacterId());
        }
        node.put("name", character.getName());
        node.put("keyword", character.getDesignKeyword());
        node.put("personality", character.getPersonality());
        node.set("stats", stats(character));
        node.put("battlePower", character.getBattlePower());
        node.put("emotion", character.getEmotion().name().toLowerCase(Locale.ROOT));
        node.put("isEvolved", character.isEvolved());
        putStatus(node, "imageStatus", character.getImageStatus());
        putSprite(node, "spriteSheetUrl", character.getSpriteSheetUrl());
        node.set("spriteMeta", spriteMeta(character.getSpriteMeta()));
        putStatus(node, "babyImageStatus", character.getBabyImageStatus());
        putSprite(node, "babySpriteSheetUrl", character.getBabySpriteSheetUrl());
        node.set("babySpriteMeta", spriteMeta(character.getBabySpriteMeta()));
        putStatus(node, "evolvedImageStatus", character.getEvolvedImageStatus());
        putSprite(node, "evolvedSpriteSheetUrl", character.getEvolvedSpriteSheetUrl());
        node.set("evolvedSpriteMeta", spriteMeta(character.getEvolvedSpriteMeta()));
        node.put("active", character.isActive());
        node.put("message", character.getStatusMessage());
        node.put("createdAt", character.getCreatedAt().toString());
        return node;
    }

    private void putStatus(ObjectNode node, String fieldName, CharacterImageStatus status) {
        if (status == null) {
            node.set(fieldName, NullNode.instance);
            return;
        }
        node.put(fieldName, status.name());
    }

    private void putSprite(ObjectNode node, String fieldName, String spriteSheetUrl) {
        if (spriteSheetUrl == null) {
            node.set(fieldName, NullNode.instance);
            return;
        }
        // Stored value may be an s3:// key; mint a fresh presigned GET URL at
        // read time. Non-S3 values (bundled fallback sprite) pass through.
        node.put(fieldName, presignService.toDisplayUrl(spriteSheetUrl));
    }

    private JsonNode spriteMeta(String spriteMeta) {
        if (spriteMeta == null || spriteMeta.isBlank()) {
            return NullNode.instance;
        }
        try {
            return objectMapper.readTree(spriteMeta);
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
