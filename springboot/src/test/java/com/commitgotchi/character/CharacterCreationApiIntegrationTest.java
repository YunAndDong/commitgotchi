package com.commitgotchi.character;

import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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
class CharacterCreationApiIntegrationTest extends PostgresIntegrationTest {

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

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void createsFirstCharacterAsActiveNormalizedProjection() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");

        MvcResult created = createCharacter(user.bearer(), "Commit Buddy", "green study slime", "Kind but precise")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").isNumber())
                .andExpect(jsonPath("$.item.name").value("Commit Buddy"))
                .andExpect(jsonPath("$.item.keyword").value("green study slime"))
                .andExpect(jsonPath("$.item.personality").value("Kind but precise"))
                .andExpect(jsonPath("$.item.stats.algo").value(0))
                .andExpect(jsonPath("$.item.stats.cs").value(0))
                .andExpect(jsonPath("$.item.stats.db").value(0))
                .andExpect(jsonPath("$.item.stats.net").value(0))
                .andExpect(jsonPath("$.item.stats.fw").value(0))
                .andExpect(jsonPath("$.item.emotion").value("joy"))
                .andExpect(jsonPath("$.item.isEvolved").value(false))
                .andExpect(jsonPath("$.item.imageStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.item.spriteSheetUrl").value("https://cdn.commitgotchi.local/sprites/fallback-default.png"))
                .andExpect(jsonPath("$.item.spriteMeta.frameMap.baby.joy[0]").value(0))
                .andExpect(jsonPath("$.item.active").value(true))
                .andExpect(jsonPath("$.item.message").value("Ready to learn"))
                .andExpect(jsonPath("$.item.createdAt").isString())
                .andExpect(jsonPath("$.state.characters[0].active").value(true))
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(0))
                .andExpect(jsonPath("$.state.dailyReport.characterId").isNumber())
                .andReturn();

        Number characterId = JsonPath.read(created.getResponse().getContentAsString(), "$.item.id");
        Number dailyReportCharacterId = JsonPath.read(
                created.getResponse().getContentAsString(),
                "$.state.dailyReport.characterId"
        );
        assertThat(dailyReportCharacterId.longValue()).isEqualTo(characterId.longValue());
        assertThat(characterRepository.countByUserId(user.id())).isEqualTo(1);
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);

        mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].id").value(characterId))
                .andExpect(jsonPath("$.state.characters[0].imageStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.state.characters[0].spriteSheetUrl").value("https://cdn.commitgotchi.local/sprites/fallback-default.png"))
                .andExpect(jsonPath("$.state.characters[0].spriteMeta.frameMap.mature.angry[1]").value(2))
                .andExpect(jsonPath("$.state.characters[0].active").value(true));
    }

    @Test
    void newCharacterBecomesActiveAndPreviousCharacterIsDeactivated() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        createCharacter(user.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk());

        createCharacter(user.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.name").value("Second"))
                .andExpect(jsonPath("$.item.active").value(true))
                .andExpect(jsonPath("$.state.characters[0].name").value("Second"));

        List<LearningCharacter> characters = characterRepository.findAllByUserIdOrderByCreatedAtDesc(user.id());
        assertThat(characters).hasSize(2);
        assertThat(characters).filteredOn(LearningCharacter::isActive)
                .singleElement()
                .extracting(LearningCharacter::getName)
                .isEqualTo("Second");
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);

        MvcResult state = mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> projected = JsonPath.read(
                state.getResponse().getContentAsString(),
                "$.state.characters"
        );
        assertThat(projected).hasSize(2);
        assertThat(projected).filteredOn(character -> Boolean.TRUE.equals(character.get("active")))
                .singleElement()
                .extracting(character -> character.get("name"))
                .isEqualTo("Second");
    }

    @Test
    void characterMutationsPersistInNormalizedRowsAcrossStateReloads() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult first = createCharacter(user.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn();
        MvcResult second = createCharacter(user.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andReturn();
        Number firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.item.id");
        Number secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.item.id");

        mockMvc.perform(patch("/api/game/characters/{id}/active", firstId)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.name").value("First"))
                .andExpect(jsonPath("$.item.active").value(true));

        MvcResult activeState = mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> activeCharacters = JsonPath.read(
                activeState.getResponse().getContentAsString(),
                "$.state.characters"
        );
        assertThat(activeCharacters).filteredOn(character -> Boolean.TRUE.equals(character.get("active")))
                .singleElement()
                .extracting(character -> character.get("name"))
                .isEqualTo("First");

        mockMvc.perform(patch("/api/game/characters/{id}", firstId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"name":"Renamed First","keyword":"renamed keyword","personality":"renamed personality"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.name").value("Renamed First"))
                .andExpect(jsonPath("$.item.keyword").value("renamed keyword"))
                .andExpect(jsonPath("$.item.personality").value("renamed personality"));

        mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[1].name").value("Renamed First"));

        mockMvc.perform(delete("/api/game/characters/{id}", firstId)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.name").value("Renamed First"));

        MvcResult remainingState = mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        List<String> remainingNames = JsonPath.read(
                remainingState.getResponse().getContentAsString(),
                "$.state.characters[*].name"
        );
        assertThat(remainingNames).containsExactly("Second");
        assertThat(characterRepository.findById(firstId.longValue())).isEmpty();
        assertThat(characterRepository.findById(secondId.longValue())).isPresent();
    }

    @Test
    void reportQuizAndDailyMutationsPersistNormalizedCharacterStateAcrossReloads() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = createCharacter(user.bearer(), "Commit Buddy", "green study slime", "Kind but precise")
                .andExpect(status().isOk())
                .andReturn();
        String quizId = JsonPath.read(created.getResponse().getContentAsString(), "$.state.quizzes[0].id");

        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"mood":"sad","title":"JPA locking","content":"Studied pessimistic locks","tags":["db"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].emotion").value("sad"))
                .andExpect(jsonPath("$.state.characters[0].message").value("힘든 날도 있지. 기록한 것만으로 충분해."));

        mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].emotion").value("sad"))
                .andExpect(jsonPath("$.state.characters[0].message").value("힘든 날도 있지. 기록한 것만으로 충분해."));

        mockMvc.perform(post("/api/game/quizzes/{id}/submit", quizId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"userAnswer":"그리디 전제 때문에 음수 간선이 있으면 확정한 최단거리를 다시 갱신하지 못합니다."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(12))
                .andExpect(jsonPath("$.state.characters[0].emotion").value("joy"));

        mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(12))
                .andExpect(jsonPath("$.state.characters[0].emotion").value("joy"));

        mockMvc.perform(post("/api/game/daily-report/deliver")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(15))
                .andExpect(jsonPath("$.state.characters[0].stats.net").value(1));

        mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(15))
                .andExpect(jsonPath("$.state.characters[0].stats.net").value(1))
                .andExpect(jsonPath("$.state.characters[0].message")
                        .value("어제 공부가 몸에 스며들었어! 레이더가 차오르는 게 느껴져."));
    }

    @Test
    void rejectsFourthCharacterWithCommonErrorResponse() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        createCharacter(user.bearer(), "One", "one keyword", "one personality").andExpect(status().isOk());
        createCharacter(user.bearer(), "Two", "two keyword", "two personality").andExpect(status().isOk());
        createCharacter(user.bearer(), "Three", "three keyword", "three personality").andExpect(status().isOk());

        createCharacter(user.bearer(), "Four", "four keyword", "four personality")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("CHARACTER_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        assertThat(characterRepository.countByUserId(user.id())).isEqualTo(3);
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
    }

    @Test
    void concurrentCreationWhenUserAlreadyHasTwoCharactersAllowsAtMostOneSuccess() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        createCharacter(user.bearer(), "One", "one keyword", "one personality").andExpect(status().isOk());
        createCharacter(user.bearer(), "Two", "two keyword", "two personality").andExpect(status().isOk());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(concurrentCreate(start, user.bearer(), "Three A"));
            Future<Integer> second = executor.submit(concurrentCreate(start, user.bearer(), "Three B"));
            start.countDown();

            List<Integer> statuses = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(statuses).filteredOn(status -> status >= 200 && status < 300).hasSizeLessThanOrEqualTo(1);
            assertThat(statuses).contains(400);
        } finally {
            executor.shutdownNow();
        }

        assertThat(characterRepository.countByUserId(user.id())).isEqualTo(3);
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
    }

    @Test
    void unauthenticatedGameStateAndCharacterCreationReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/game/state"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"));

        mockMvc.perform(post("/api/game/characters")
                        .contentType("application/json")
                        .content(validCreateJson("Ghost", "ghost keyword", "ghost personality")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"));
    }

    @Test
    void stateProjectionOnlyIncludesCharactersOwnedByAuthenticatedUser() throws Exception {
        AdminTestFixture.ProvisionedUser first = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser second = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        createCharacter(first.bearer(), "First User Character", "first keyword", "first personality")
                .andExpect(status().isOk());
        createCharacter(second.bearer(), "Second User Character", "second keyword", "second personality")
                .andExpect(status().isOk());

        MvcResult firstState = mockMvc.perform(get("/api/game/state").header("Authorization", first.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        List<String> firstNames = JsonPath.read(firstState.getResponse().getContentAsString(), "$.state.characters[*].name");
        assertThat(firstNames).containsExactly("First User Character");

        MvcResult secondState = mockMvc.perform(get("/api/game/state").header("Authorization", second.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        List<String> secondNames = JsonPath.read(secondState.getResponse().getContentAsString(), "$.state.characters[*].name");
        assertThat(secondNames).containsExactly("Second User Character");
    }

    @Test
    void rejectsBlankTooLongAndUnknownSystemOwnedFields() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");

        createRaw(user.bearer(), """
                {"name":" ","keyword":"keyword","personality":"personality"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        createRaw(user.bearer(), """
                {"name":"Valid","keyword":"%s","personality":"personality"}
                """.formatted("k".repeat(121)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        createRaw(user.bearer(), """
                {"name":"Valid","keyword":"keyword","personality":"personality","active":true}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Authorization"))));

        assertThat(characterRepository.countByUserId(user.id())).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions createCharacter(
            String bearer,
            String name,
            String keyword,
            String personality
    ) throws Exception {
        return createRaw(bearer, validCreateJson(name, keyword, personality));
    }

    private org.springframework.test.web.servlet.ResultActions createRaw(String bearer, String json) throws Exception {
        return mockMvc.perform(post("/api/game/characters")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(json));
    }

    private Callable<Integer> concurrentCreate(CountDownLatch start, String bearer, String name) {
        return () -> {
            start.await();
            MvcResult result = createCharacter(bearer, name, name.toLowerCase() + " keyword", "parallel personality")
                    .andReturn();
            return result.getResponse().getStatus();
        };
    }

    private String validCreateJson(String name, String keyword, String personality) {
        return """
                {"name":"%s","keyword":"%s","personality":"%s"}
                """.formatted(name, keyword, personality);
    }

    private long activeCharacterCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE user_id = ? AND is_active = true",
                Long.class,
                userId
        );
        return count == null ? 0 : count;
    }

    private String uniqueEmail() {
        return "character-api-" + UUID.randomUUID() + "@example.com";
    }
}
