package com.commitgotchi.character;

import com.commitgotchi.character.image.CharacterImageClient;
import com.commitgotchi.character.image.CharacterImageGenerationRequest;
import com.commitgotchi.character.image.CharacterImageGenerationResult;
import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "commitgotchi.character.image.enabled=true",
        "commitgotchi.character.image.base-url=http://fastapi.test",
        "commitgotchi.character.image.fallback-sprite-sheet-url=https://cdn.commitgotchi.local/sprites/test-fallback.png",
        "commitgotchi.character.image.s3-presigned-url-enabled=true",
        "commitgotchi.character.image.s3-region=ap-northeast-2",
        "commitgotchi.character.image.s3-access-key-id=test-access-key",
        "commitgotchi.character.image.s3-secret-access-key=test-secret-key"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CharacterImageGenerationApiIntegrationTest extends PostgresIntegrationTest {

    private static final String READY_URL = "https://cdn.example.com/sprites/users/1/commitgotchi.png";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeCharacterImageClient imageClient;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
        imageClient.reset();
    }

    @Test
    void creationWithSuccessfulImageClientReturnsReadyAndFreshSpriteProjection() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        imageClient.succeed(READY_URL, spriteMetaJson());

        MvcResult created = createCharacter(user.bearer(), "Commit Buddy", "green study slime", "Kind but precise")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.imageStatus").value("READY"))
                .andExpect(jsonPath("$.item.evolvedImageStatus").value("READY"))
                .andExpect(jsonPath("$.item.evolvedSpriteSheetUrl").value(READY_URL))
                .andExpect(jsonPath("$.item.spriteMeta.frameMap.joy[0]").value(0))
                .andExpect(jsonPath("$.item.spriteMeta.frameMap.angry[1]").value(2))
                .andExpect(jsonPath("$.state.characters[0].imageStatus").value("READY"))
                .andExpect(jsonPath("$.state.characters[0].evolvedSpriteSheetUrl").value(READY_URL))
                .andExpect(jsonPath("$.state.characters[0].spriteMeta.frameMap.sad[1]").value(1))
                .andReturn();

        Number characterId = JsonPath.read(created.getResponse().getContentAsString(), "$.item.id");
        Number catalogCharacterId = JsonPath.read(created.getResponse().getContentAsString(), "$.item.catalogCharacterId");
        String itemSpriteSheetUrl = JsonPath.read(created.getResponse().getContentAsString(), "$.item.spriteSheetUrl");
        assertThat(itemSpriteSheetUrl).isEqualTo(babyPresetSpriteSheetUrl(characterId));
        CharacterImageGenerationRequest request = imageClient.lastRequest();
        assertThat(request.userId()).isEqualTo(user.id());
        assertThat(request.characterId()).isEqualTo(catalogCharacterId.longValue());
        assertThat(request.designKeyword()).isEqualTo("green study slime");
        assertThat(request.s3ObjectUrl())
                .startsWith("https://commitgotchi-character-images.s3.ap-northeast-2.amazonaws.com/sprites")
                .contains("/characters/" + catalogCharacterId + "/sprite-sheet.png")
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-Signature=");
        assertThat(request.prompt()).isEqualTo("green study slime");

        Map<String, Object> stored = imageColumns(characterId);
        assertThat(stored)
                .containsEntry("image_status", "READY")
                .containsEntry("sprite_sheet_url", READY_URL);

        MvcResult state = mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].imageStatus").value("READY"))
                .andExpect(jsonPath("$.state.characters[0].evolvedSpriteSheetUrl").value(READY_URL))
                .andExpect(jsonPath("$.state.characters[0].spriteMeta.frameMap.joy[0]").value(0))
                .andReturn();
        String stateSpriteSheetUrl = JsonPath.read(state.getResponse().getContentAsString(), "$.state.characters[0].spriteSheetUrl");
        assertThat(stateSpriteSheetUrl).isEqualTo(babyPresetSpriteSheetUrl(characterId));

        jdbcTemplate.update(
                """
                        UPDATE user_character
                        SET stat_algorithm = 1000,
                            battle_power = 1000,
                            is_evolved = true
                        WHERE id = ?
                        """,
                characterId.longValue()
        );
        mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].isEvolved").value(true))
                .andExpect(jsonPath("$.state.characters[0].spriteSheetUrl").value(READY_URL))
                .andExpect(jsonPath("$.state.characters[0].spriteMeta.frameMap.joy[0]").value(0));
    }

    @Test
    void creationWithFailedImageClientFallsBackWithoutRollingBackCharacterCreation() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        imageClient.fail("FASTAPI_FAIL");

        MvcResult created = createCharacter(user.bearer(), "Fallback Buddy", "blue data turtle", "Steady learner")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.imageStatus").value("READY"))
                .andExpect(jsonPath("$.item.evolvedImageStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.item.evolvedSpriteSheetUrl").value("https://cdn.commitgotchi.local/sprites/test-fallback.png"))
                .andExpect(jsonPath("$.item.spriteMeta.frameMap.joy[0]").value(0))
                .andExpect(jsonPath("$.state.characters[0].active").value(true))
                .andExpect(jsonPath("$.state.characters[0].imageStatus").value("READY"))
                .andExpect(jsonPath("$.state.characters[0].evolvedImageStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.state.characters[0].spriteMeta.frameMap.sad[1]").value(1))
                .andReturn();

        Number characterId = JsonPath.read(created.getResponse().getContentAsString(), "$.item.id");
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
        assertThat(imageColumns(characterId))
                .containsEntry("image_status", "FALLBACK")
                .containsEntry("sprite_sheet_url", "https://cdn.commitgotchi.local/sprites/test-fallback.png");
    }

    @Test
    void creationWithExceptionalImageClientFallsBackWithoutLeakingFailureDetails() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        imageClient.throwNext(new IllegalStateException("simulated Authorization Bearer raw stack trace"));

        MvcResult created = createCharacter(user.bearer(), "Exception Buddy", "timeout keyword", "Calm under pressure")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.imageStatus").value("READY"))
                .andExpect(jsonPath("$.item.evolvedImageStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.item.evolvedSpriteSheetUrl").value("https://cdn.commitgotchi.local/sprites/test-fallback.png"))
                .andExpect(content().string(not(containsString("Authorization"))))
                .andExpect(content().string(not(containsString("Bearer"))))
                .andExpect(content().string(not(containsString("simulated"))))
                .andReturn();

        Number characterId = JsonPath.read(created.getResponse().getContentAsString(), "$.item.id");
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
        assertThat(imageColumns(characterId)).containsEntry("image_status", "FALLBACK");
    }

    @Test
    void creationWithTimeoutEquivalentFailureFallsBack() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        imageClient.fail("TIMEOUT");

        createCharacter(user.bearer(), "Timeout Buddy", "slow image keyword", "Patient learner")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.imageStatus").value("READY"))
                .andExpect(jsonPath("$.item.evolvedImageStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.item.evolvedSpriteSheetUrl").value("https://cdn.commitgotchi.local/sprites/test-fallback.png"))
                .andExpect(jsonPath("$.item.spriteMeta.frameMap.angry[1]").value(2));

        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
    }

    @Test
    void retryReadyCharacterIsNoOpAndDoesNotCallImageClientAgain() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        imageClient.succeed(READY_URL, spriteMetaJson());
        Number characterId = itemId(createCharacter(user.bearer(), "Ready", "ready keyword", "ready personality")
                .andExpect(status().isOk())
                .andReturn());
        imageClient.resetCalls();

        retryImage(user.bearer(), characterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.imageStatus").value("READY"))
                .andExpect(jsonPath("$.item.evolvedImageStatus").value("READY"))
                .andExpect(jsonPath("$.item.evolvedSpriteSheetUrl").value(READY_URL))
                .andExpect(jsonPath("$.state.characters[0].imageStatus").value("READY"));

        assertThat(imageClient.callCount()).isZero();
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
    }

    @Test
    void retryFallbackCharacterRunsImageClientAndCanBecomeReady() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        imageClient.fail("FIRST_FAIL");
        Number characterId = itemId(createCharacter(user.bearer(), "Retry", "retry keyword", "retry personality")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.imageStatus").value("READY"))
                .andExpect(jsonPath("$.item.evolvedImageStatus").value("FALLBACK"))
                .andReturn());

        imageClient.resetCalls();
        imageClient.succeed(READY_URL, spriteMetaJson());
        retryImage(user.bearer(), characterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.imageStatus").value("READY"))
                .andExpect(jsonPath("$.item.evolvedImageStatus").value("READY"))
                .andExpect(jsonPath("$.item.evolvedSpriteSheetUrl").value(READY_URL))
                .andExpect(jsonPath("$.state.characters[0].spriteMeta.frameMap.joy[0]").value(0));

        assertThat(imageClient.callCount()).isEqualTo(1);
        assertThat(imageColumns(characterId)).containsEntry("image_status", "READY");
    }

    @Test
    void retryHidesMalformedMissingCrossOwnerAndUnauthenticatedRequestsSafely() throws Exception {
        AdminTestFixture.ProvisionedUser owner = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser other = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        imageClient.fail("FALLBACK");
        Number otherCharacterId = itemId(createCharacter(other.bearer(), "Other", "other keyword", "other personality")
                .andExpect(status().isOk())
                .andReturn());

        expectNotFoundWithoutSensitiveDetails(retryImage(owner.bearer(), "not-a-number"));
        expectNotFoundWithoutSensitiveDetails(retryImage(owner.bearer(), 999_999_999L));
        expectNotFoundWithoutSensitiveDetails(retryImage(owner.bearer(), otherCharacterId));

        mockMvc.perform(post("/api/game/characters/{id}/retry-image", otherCharacterId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"))
                .andExpect(content().string(not(containsString("Authorization"))));
    }

    private ResultActions createCharacter(String bearer, String name, String keyword, String personality)
            throws Exception {
        return mockMvc.perform(post("/api/game/characters")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("""
                        {"name":"%s","keyword":"%s","personality":"%s"}
                        """.formatted(name, keyword, personality)));
    }

    private ResultActions retryImage(String bearer, Object characterId) throws Exception {
        return mockMvc.perform(post("/api/game/characters/{id}/retry-image", characterId)
                .header("Authorization", bearer));
    }

    private void expectNotFoundWithoutSensitiveDetails(ResultActions actions) throws Exception {
        actions.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Authorization"))))
                .andExpect(content().string(not(containsString("Bearer"))))
                .andExpect(content().string(not(containsString("stackTrace"))))
                .andExpect(content().string(not(containsString("DataIntegrityViolationException"))));
    }

    private Number itemId(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.item.id");
    }

    private Map<String, Object> imageColumns(Number characterId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT image_status, sprite_sheet_url, sprite_meta
                        FROM characters
                        WHERE id = (
                            SELECT character_id
                            FROM user_character
                            WHERE id = ?
                        )
                        """,
                characterId.longValue()
        );
    }

    private long activeCharacterCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_character WHERE user_id = ? AND is_active = true AND deleted_at IS NULL",
                Long.class,
                userId
        );
        return count == null ? 0 : count;
    }

    private String babyPresetSpriteSheetUrl(Number characterId) {
        long presetId = (characterId.longValue() % 3L) + 1L;
        return "s3://commitgotchi-character-images/sprites/" + presetId + "/sprite-sheet.png";
    }

    private String uniqueEmail() {
        return "character-image-" + UUID.randomUUID() + "@example.com";
    }

    private String spriteMetaJson() {
        return """
                {
                  "columns": 3,
                  "rows": 1,
                  "frameMap": {
                    "joy": [0, 0],
                    "sad": [0, 1],
                    "angry": [0, 2]
                  },
                  "transparent": true
                }
                """;
    }

    @TestConfiguration
    static class FakeImageClientConfig {

        @Bean
        @Primary
        FakeCharacterImageClient fakeCharacterImageClient() {
            return new FakeCharacterImageClient();
        }
    }

    static final class FakeCharacterImageClient implements CharacterImageClient {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile CharacterImageGenerationRequest lastRequest;
        private volatile CharacterImageGenerationResult nextResult =
                CharacterImageGenerationResult.failure("DEFAULT_FAKE_FAILURE");
        private volatile RuntimeException nextException;

        @Override
        public CharacterImageGenerationResult generate(CharacterImageGenerationRequest request) {
            calls.incrementAndGet();
            lastRequest = request;
            if (nextException != null) {
                RuntimeException exception = nextException;
                nextException = null;
                throw exception;
            }
            return nextResult;
        }

        void succeed(String spriteSheetUrl, String spriteMeta) {
            nextResult = CharacterImageGenerationResult.success(spriteSheetUrl, spriteMeta);
        }

        void fail(String reason) {
            nextResult = CharacterImageGenerationResult.failure(reason);
        }

        void throwNext(RuntimeException exception) {
            nextException = exception;
        }

        void reset() {
            resetCalls();
            fail("DEFAULT_FAKE_FAILURE");
        }

        void resetCalls() {
            calls.set(0);
            lastRequest = null;
            nextException = null;
        }

        int callCount() {
            return calls.get();
        }

        CharacterImageGenerationRequest lastRequest() {
            return lastRequest;
        }
    }
}
