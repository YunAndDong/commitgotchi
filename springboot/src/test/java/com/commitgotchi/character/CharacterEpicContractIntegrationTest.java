package com.commitgotchi.character;

import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CharacterEpicContractIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LearningCharacterRepository characterRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void coversCharacterCrudActivationRetryAndDeleteInOneAuthenticatedFlow() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");

        Number firstId = itemId(createCharacter(user.bearer(), "One", "one keyword", "one personality")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.active").value(true))
                .andExpect(jsonPath("$.item.imageStatus").value("FALLBACK"))
                .andReturn());
        Number secondId = itemId(createCharacter(user.bearer(), "Two", "two keyword", "two personality")
                .andExpect(status().isOk())
                .andReturn());
        Number thirdId = itemId(createCharacter(user.bearer(), "Three", "three keyword", "three personality")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters.length()").value(3))
                .andReturn());

        createCharacter(user.bearer(), "Four", "four keyword", "four personality")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARACTER_LIMIT_EXCEEDED"));

        getCharacter(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(firstId))
                .andExpect(jsonPath("$.item.name").value("One"));

        updateCharacter(user.bearer(), firstId, """
                {"name":"One Renamed","keyword":"one renamed keyword","personality":"one renamed personality"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.name").value("One Renamed"))
                .andExpect(jsonPath("$.item.keyword").value("one renamed keyword"))
                .andExpect(jsonPath("$.item.personality").value("one renamed personality"));

        activateCharacter(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(firstId))
                .andExpect(jsonPath("$.item.active").value(true));

        retryImage(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(firstId))
                .andExpect(jsonPath("$.item.imageStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.item.spriteSheetUrl").isNotEmpty());

        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
        assertThat(activeCharacterId(user.id())).isEqualTo(firstId.longValue());

        deleteCharacter(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(firstId))
                .andExpect(jsonPath("$.state.characters.length()").value(2))
                .andExpect(jsonPath("$.state.characters[0].id").value(thirdId))
                .andExpect(jsonPath("$.state.characters[0].active").value(true));

        assertThat(characterRepository.findById(firstId.longValue())).isEmpty();
        assertThat(characterRepository.findById(secondId.longValue())).isPresent();
        assertThat(characterRepository.findById(thirdId.longValue())).isPresent();
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
        assertThat(activeCharacterId(user.id())).isEqualTo(thirdId.longValue());
    }

    @Test
    void keepsGameStateCharactersEmptyWhileProjectingNormalizedRowsAndCompatibilityData() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = createCharacter(user.bearer(), "Projection", "projection keyword", "projection personality")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].spriteSheetUrl").isNotEmpty())
                .andExpect(jsonPath("$.state.quizzes.length()").value(2))
                .andReturn();
        Number firstId = itemId(created);
        String firstQuizId = JsonPath.read(created.getResponse().getContentAsString(), "$.state.quizzes[0].id");

        assertStoredCharactersEmpty(user.id());
        assertStoredStateDoesNotContain(user.id(), "spriteSheetUrl");
        assertThat(imageColumns(firstId).get("sprite_sheet_url")).isNotNull();
        assertThat(imageColumns(firstId).get("sprite_meta")).isNotNull();

        updateCharacter(user.bearer(), firstId, """
                {"name":"Projection Updated","keyword":"projection updated keyword","personality":"projection updated personality"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].name").value("Projection Updated"));
        assertStoredCharactersEmpty(user.id());

        retryImage(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.spriteSheetUrl").isNotEmpty())
                .andExpect(jsonPath("$.state.characters[0].spriteMeta.frameMap.baby.joy[0]").value(0));
        assertStoredCharactersEmpty(user.id());
        assertStoredStateDoesNotContain(user.id(), "spriteMeta");

        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"mood":"joy","title":"Projection report","content":"Keep compatibility data","tags":["algo"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(firstId.toString()))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(firstId.toString()));
        assertStoredCharactersEmpty(user.id());

        mockMvc.perform(post("/api/game/quizzes/{id}/submit", firstQuizId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"selected":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(12))
                .andExpect(jsonPath("$.state.characters[0].battlePower").value(12));
        assertStoredCharactersEmpty(user.id());

        mockMvc.perform(post("/api/game/daily-report/deliver")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(firstId.toString()))
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(15))
                .andExpect(jsonPath("$.state.characters[0].stats.net").value(1))
                .andExpect(jsonPath("$.state.characters[0].battlePower").value(16));
        assertStoredCharactersEmpty(user.id());
        assertStoredStateDoesNotContain(user.id(), "spriteMeta");

        Number secondId = itemId(createCharacter(user.bearer(), "Replacement", "replacement keyword", "replacement personality")
                .andExpect(status().isOk())
                .andReturn());
        deleteCharacter(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters.length()").value(1))
                .andExpect(jsonPath("$.state.characters[0].id").value(secondId))
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(secondId))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(firstId.toString()));

        JsonNode stored = storedState(user.id());
        assertThat(stored.get("characters")).isNotNull();
        assertThat(stored.get("characters").isArray()).isTrue();
        assertThat(stored.get("characters").size()).isZero();
        assertThat(stored.path("dailyReport").path("characterId").asLong()).isEqualTo(secondId.longValue());
        assertThat(stored.path("reports").get(0).path("characterId").asText()).isEqualTo(firstId.toString());

        mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].id").value(secondId))
                .andExpect(jsonPath("$.state.characters[0].spriteSheetUrl").isNotEmpty())
                .andExpect(jsonPath("$.state.characters[0].spriteMeta.frameMap.mature.angry[1]").value(2));
    }

    @Test
    void hidesMalformedMissingCrossOwnerIdsAndMissingAuthWithoutSensitiveDetails() throws Exception {
        AdminTestFixture.ProvisionedUser owner = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser other = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number otherCharacterId = itemId(createCharacter(other.bearer(), "Other", "other keyword", "other personality")
                .andExpect(status().isOk())
                .andReturn());

        expectNotFoundWithoutSensitiveDetails(getCharacter(owner.bearer(), "not-a-number"));
        expectNotFoundWithoutSensitiveDetails(getCharacter(owner.bearer(), 999_999_999L));
        expectNotFoundWithoutSensitiveDetails(getCharacter(owner.bearer(), otherCharacterId));
        expectNotFoundWithoutSensitiveDetails(updateCharacter(owner.bearer(), otherCharacterId, validUpdateJson()));
        expectNotFoundWithoutSensitiveDetails(deleteCharacter(owner.bearer(), otherCharacterId));
        expectNotFoundWithoutSensitiveDetails(activateCharacter(owner.bearer(), otherCharacterId));
        expectNotFoundWithoutSensitiveDetails(retryImage(owner.bearer(), otherCharacterId));

        mockMvc.perform(get("/api/game/state"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"))
                .andExpect(content().string(not(containsString("Authorization"))));

        mockMvc.perform(post("/api/game/characters/{id}/retry-image", otherCharacterId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"))
                .andExpect(content().string(not(containsString("Bearer"))));
    }

    @Test
    void concurrentActivationLeavesAtMostOneActiveCharacterForUser() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number firstId = itemId(createCharacter(user.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn());
        Number secondId = itemId(createCharacter(user.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andReturn());
        createCharacter(user.bearer(), "Third", "third keyword", "third personality")
                .andExpect(status().isOk());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(concurrentActivate(start, user.bearer(), firstId));
            Future<Integer> second = executor.submit(concurrentActivate(start, user.bearer(), secondId));
            start.countDown();

            List<Integer> statuses = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(statuses).allSatisfy(status -> assertThat(status).isEqualTo(200));
        } finally {
            executor.shutdownNow();
        }

        Set<Long> requestedIds = Set.of(firstId.longValue(), secondId.longValue());
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
        assertThat(requestedIds).contains(activeCharacterId(user.id()));
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

    private ResultActions getCharacter(String bearer, Object characterId) throws Exception {
        return mockMvc.perform(get("/api/game/characters/{id}", characterId)
                .header("Authorization", bearer));
    }

    private ResultActions updateCharacter(String bearer, Object characterId, String json) throws Exception {
        return mockMvc.perform(patch("/api/game/characters/{id}", characterId)
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(json));
    }

    private ResultActions activateCharacter(String bearer, Object characterId) throws Exception {
        return mockMvc.perform(patch("/api/game/characters/{id}/active", characterId)
                .header("Authorization", bearer));
    }

    private ResultActions retryImage(String bearer, Object characterId) throws Exception {
        return mockMvc.perform(post("/api/game/characters/{id}/retry-image", characterId)
                .header("Authorization", bearer));
    }

    private ResultActions deleteCharacter(String bearer, Object characterId) throws Exception {
        return mockMvc.perform(delete("/api/game/characters/{id}", characterId)
                .header("Authorization", bearer));
    }

    private Callable<Integer> concurrentActivate(CountDownLatch start, String bearer, Number characterId) {
        return () -> {
            start.await();
            return activateCharacter(bearer, characterId)
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };
    }

    private void expectNotFoundWithoutSensitiveDetails(ResultActions actions) throws Exception {
        actions.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Authorization"))))
                .andExpect(content().string(not(containsString("Bearer"))))
                .andExpect(content().string(not(containsString("stackTrace"))))
                .andExpect(content().string(not(containsString("DataIntegrityViolationException"))))
                .andExpect(content().string(not(containsString("constraint"))))
                .andExpect(content().string(not(containsString("uq_"))))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    private Number itemId(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.item.id");
    }

    private String validUpdateJson() {
        return """
                {"name":"Renamed","keyword":"renamed keyword","personality":"renamed personality"}
                """;
    }

    private void assertStoredCharactersEmpty(long userId) throws Exception {
        JsonNode characters = storedState(userId).get("characters");
        assertThat(characters).isNotNull();
        assertThat(characters.isArray()).isTrue();
        assertThat(characters.size()).isZero();
    }

    private void assertStoredStateDoesNotContain(long userId, String text) {
        String json = jdbcTemplate.queryForObject(
                "SELECT state_json FROM game_states WHERE user_id = ?",
                String.class,
                userId
        );
        assertThat(json).doesNotContain(text);
    }

    private JsonNode storedState(long userId) throws Exception {
        String json = jdbcTemplate.queryForObject(
                "SELECT state_json FROM game_states WHERE user_id = ?",
                String.class,
                userId
        );
        return objectMapper.readTree(json);
    }

    private java.util.Map<String, Object> imageColumns(Number characterId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT image_status, sprite_sheet_url, sprite_meta
                        FROM characters
                        WHERE id = ?
                        """,
                characterId.longValue()
        );
    }

    private long activeCharacterCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE user_id = ? AND is_active = true",
                Long.class,
                userId
        );
        return count == null ? 0 : count;
    }

    private long activeCharacterId(long userId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM characters WHERE user_id = ? AND is_active = true",
                Long.class,
                userId
        );
        return id == null ? 0 : id;
    }

    private String uniqueEmail() {
        return "character-epic-" + UUID.randomUUID() + "@example.com";
    }
}
