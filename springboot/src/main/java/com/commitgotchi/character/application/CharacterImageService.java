package com.commitgotchi.character.application;

import com.commitgotchi.character.domain.CharacterImageStatus;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.character.image.CharacterImageClient;
import com.commitgotchi.character.image.CharacterImageGenerationRequest;
import com.commitgotchi.character.image.CharacterImageGenerationResult;
import com.commitgotchi.character.image.CharacterImageProperties;
import com.commitgotchi.character.image.CharacterImagePromptFactory;
import com.commitgotchi.character.image.CharacterImageStorageUrlFactory;
import com.commitgotchi.character.image.CharacterSpriteMetaFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Service
public class CharacterImageService {

    private static final Logger log = LoggerFactory.getLogger(CharacterImageService.class);

    private final LearningCharacterRepository characterRepository;
    private final CharacterImageClient imageClient;
    private final CharacterImageProperties imageProperties;
    private final CharacterImagePromptFactory promptFactory;
    private final CharacterImageStorageUrlFactory storageUrlFactory;
    private final CharacterSpriteMetaFactory spriteMetaFactory;
    private final TransactionTemplate transactionTemplate;

    public CharacterImageService(
            LearningCharacterRepository characterRepository,
            CharacterImageClient imageClient,
            CharacterImageProperties imageProperties,
            CharacterImagePromptFactory promptFactory,
            CharacterImageStorageUrlFactory storageUrlFactory,
            CharacterSpriteMetaFactory spriteMetaFactory,
            PlatformTransactionManager transactionManager
    ) {
        this.characterRepository = characterRepository;
        this.imageClient = imageClient;
        this.imageProperties = imageProperties;
        this.promptFactory = promptFactory;
        this.storageUrlFactory = storageUrlFactory;
        this.spriteMetaFactory = spriteMetaFactory;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public LearningCharacter generateOrFallback(long userId, long characterId) {
        LearningCharacter character = characterRepository.findByIdAndUserId(characterId, userId)
                .orElseThrow(CharacterNotFoundException::new);
        if (character.getImageStatus() == CharacterImageStatus.READY) {
            return character;
        }

        CharacterImageGenerationResult result = imageResultFor(character, userId);
        return applyResult(userId, characterId, result);
    }

    private CharacterImageGenerationResult imageResultFor(LearningCharacter character, long userId) {
        if (!imageProperties.isEnabled()) {
            return CharacterImageGenerationResult.failure("DISABLED");
        }
        if (!imageProperties.hasBaseUrl()) {
            return CharacterImageGenerationResult.failure("BASE_URL_MISSING");
        }
        try {
            return imageClient.generate(new CharacterImageGenerationRequest(
                    userId,
                    character.getId(),
                    character.getDesignKeyword(),
                    storageUrlFactory.createStorageUrl(userId, character.getId()),
                    promptFactory.createPrompt(character.getDesignKeyword())
            ));
        } catch (RuntimeException exception) {
            log.warn(
                    "Character image client failed reason={} characterId={} traceId={}",
                    "CLIENT_EXCEPTION",
                    character.getId(),
                    MDC.get("traceId")
            );
            return CharacterImageGenerationResult.failure("CLIENT_EXCEPTION");
        }
    }

    private LearningCharacter applyResult(
            long userId,
            long characterId,
            CharacterImageGenerationResult imageResult
    ) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            LearningCharacter character = characterRepository.findByIdAndUserIdForUpdate(characterId, userId)
                    .orElseThrow(CharacterNotFoundException::new);
            if (character.getImageStatus() == CharacterImageStatus.READY) {
                return character;
            }

            if (imageResult.success()) {
                character.markReady(imageResult.spriteSheetUrl(), imageResult.spriteMeta());
            } else {
                markFallbackOrFailed(character);
            }
            return characterRepository.saveAndFlush(character);
        }));
    }

    private void markFallbackOrFailed(LearningCharacter character) {
        String fallbackSpriteSheetUrl = imageProperties.normalizedFallbackSpriteSheetUrl();
        String fallbackSpriteMeta = spriteMetaFactory.fallbackSpriteMetaJson();
        if (!StringUtils.hasText(fallbackSpriteSheetUrl) || !StringUtils.hasText(fallbackSpriteMeta)) {
            character.markFailed();
            return;
        }
        character.markFallback(fallbackSpriteSheetUrl, fallbackSpriteMeta);
    }
}
