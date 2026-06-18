package com.commitgotchi.character;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CharacterPrototypeGrowthBridgeIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void saveReportUpdatesNormalizedEmotionButPersistsNoCharacters() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number characterId = itemId(createCharacter(user.bearer(), "Reporter", "report keyword", "steady")
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"mood":"sad","title":"Bridge report","content":"Keep the row as SoR","tags":["algo","net"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.title").value("Bridge report"))
                .andExpect(jsonPath("$.item.characterId").value(characterId.toString()))
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(characterId.toString()))
                .andExpect(jsonPath("$.state.characters[0].emotion").value("sad"))
                .andExpect(jsonPath("$.state.characters[0].message").value("힘든 날도 있지. 기록한 것만으로 충분해."));

        Map<String, Object> character = characterColumns(characterId);
        assertThat(character)
                .containsEntry("emotion", "SAD")
                .containsEntry("status_message", "힘든 날도 있지. 기록한 것만으로 충분해.");
        assertStoredCharactersEmpty(user.id());

        AdminTestFixture.ProvisionedUser noCharacter = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", noCharacter.bearer())
                        .contentType("application/json")
                        .content("""
                                {"mood":"joy","title":"No active","content":"No character should be created","tags":["db"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item").value(nullValue()))
                .andExpect(jsonPath("$.state.characters.length()").value(0))
                .andExpect(jsonPath("$.state.reports.length()").value(0));
        assertThat(characterCount(noCharacter.id())).isZero();
        assertStoredCharactersEmpty(noCharacter.id());
    }

    @Test
    void submitQuizAppliesScoreOnceAndKeepsPersistenceBoundary() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = createCharacter(user.bearer(), "Quizzer", "quiz keyword", "curious")
                .andExpect(status().isOk())
                .andReturn();
        Number characterId = itemId(created);
        String quizId = JsonPath.read(created.getResponse().getContentAsString(), "$.state.quizzes[0].id");

        mockMvc.perform(post("/api/game/quizzes/{id}/submit", quizId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"selected":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.scored").value(true))
                .andExpect(jsonPath("$.item.deltaAmount").value(12))
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(12))
                .andExpect(jsonPath("$.state.characters[0].battlePower").value(12));

        assertThat(characterColumns(characterId))
                .containsEntry("stat_algorithm", 12)
                .containsEntry("battle_power", 12);
        assertStoredCharactersEmpty(user.id());

        mockMvc.perform(post("/api/game/quizzes/{id}/submit", quizId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"selected":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.scored").value(true))
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(12))
                .andExpect(jsonPath("$.state.characters[0].battlePower").value(12));

        assertThat(characterColumns(characterId))
                .containsEntry("stat_algorithm", 12)
                .containsEntry("battle_power", 12);
        assertStoredCharactersEmpty(user.id());
    }

    @Test
    void concurrentQuizSubmissionsApplyScoreOnlyOnce() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = createCharacter(user.bearer(), "Concurrent Quiz", "quiz keyword", "careful")
                .andExpect(status().isOk())
                .andReturn();
        Number characterId = itemId(created);
        String quizId = JsonPath.read(created.getResponse().getContentAsString(), "$.state.quizzes[0].id");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(concurrentQuizSubmit(ready, start, user.bearer(), quizId));
            Future<Integer> second = executor.submit(concurrentQuizSubmit(ready, start, user.bearer(), quizId));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(200);
        } finally {
            executor.shutdownNow();
        }

        assertThat(characterColumns(characterId))
                .containsEntry("stat_algorithm", 12)
                .containsEntry("battle_power", 12);
        JsonNode storedQuiz = storedQuiz(user.id(), quizId);
        assertThat(storedQuiz.path("scored").asBoolean()).isTrue();
        assertStoredCharactersEmpty(user.id());
    }

    @Test
    void submitQuizFailureAndStaleCharacterDoNotMarkScored() throws Exception {
        AdminTestFixture.ProvisionedUser failureUser = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult failureCreated = createCharacter(failureUser.bearer(), "Fail Quiz", "quiz keyword", "careful")
                .andExpect(status().isOk())
                .andReturn();
        Number failureCharacterId = itemId(failureCreated);
        String failureQuizId = JsonPath.read(failureCreated.getResponse().getContentAsString(), "$.state.quizzes[0].id");

        mockMvc.perform(post("/api/game/quizzes/{id}/submit", failureQuizId)
                        .header("Authorization", failureUser.bearer())
                        .contentType("application/json")
                        .content("""
                                {"selected":1,"fail":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.ok").value(false))
                .andExpect(jsonPath("$.item.quiz.scored").value(false))
                .andExpect(jsonPath("$.item.quiz.gradeFailed").value(true))
                .andExpect(jsonPath("$.state.characters[0].battlePower").value(0));
        assertThat(characterColumns(failureCharacterId)).containsEntry("battle_power", 0);
        assertStoredCharactersEmpty(failureUser.id());

        AdminTestFixture.ProvisionedUser staleUser = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult staleCreated = createCharacter(staleUser.bearer(), "Stale Quiz", "stale keyword", "watchful")
                .andExpect(status().isOk())
                .andReturn();
        Number staleCharacterId = itemId(staleCreated);
        String staleQuizId = JsonPath.read(staleCreated.getResponse().getContentAsString(), "$.state.quizzes[0].id");
        deleteCharacterRow(staleCharacterId);

        mockMvc.perform(post("/api/game/quizzes/{id}/submit", staleQuizId)
                        .header("Authorization", staleUser.bearer())
                        .contentType("application/json")
                        .content("""
                                {"selected":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.scored").value(false))
                .andExpect(jsonPath("$.item.gradeFailed").value(true))
                .andExpect(jsonPath("$.item.deltaAmount").value(0))
                .andExpect(jsonPath("$.state.characters.length()").value(0));

        JsonNode storedQuiz = storedQuiz(staleUser.id(), staleQuizId);
        assertThat(storedQuiz.path("scored").asBoolean()).isFalse();
        assertThat(storedQuiz.path("gradeFailed").asBoolean()).isTrue();
        assertStoredCharactersEmpty(staleUser.id());
    }

    @Test
    void deliverDailyReportAppliesDeltasOnceAndMarksAppliedAfterGrowth() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number characterId = itemId(createCharacter(user.bearer(), "Daily", "daily keyword", "steady")
                .andExpect(status().isOk())
                .andReturn());
        saveReport(user.bearer(), "Daily report")
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/game/daily-report/deliver")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.status").value("ready"))
                .andExpect(jsonPath("$.state.reports[0].status").value("reflected"))
                .andExpect(jsonPath("$.state.reports[0].scoreApplied").value(true))
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(3))
                .andExpect(jsonPath("$.state.characters[0].stats.net").value(1))
                .andExpect(jsonPath("$.state.characters[0].battlePower").value(4));

        assertThat(characterColumns(characterId))
                .containsEntry("stat_algorithm", 3)
                .containsEntry("stat_network", 1)
                .containsEntry("battle_power", 4);
        assertStoredCharactersEmpty(user.id());

        mockMvc.perform(post("/api/game/daily-report/deliver")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(3))
                .andExpect(jsonPath("$.state.characters[0].stats.net").value(1))
                .andExpect(jsonPath("$.state.characters[0].battlePower").value(4));

        assertThat(characterColumns(characterId))
                .containsEntry("stat_algorithm", 3)
                .containsEntry("stat_network", 1)
                .containsEntry("battle_power", 4);
    }

    @Test
    void concurrentDailyReportDeliveriesApplyDeltasOnlyOnce() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number characterId = itemId(createCharacter(user.bearer(), "Concurrent Daily", "daily keyword", "steady")
                .andExpect(status().isOk())
                .andReturn());
        saveReport(user.bearer(), "Concurrent daily report")
                .andExpect(status().isOk());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(concurrentDailyDelivery(ready, start, user.bearer()));
            Future<Integer> second = executor.submit(concurrentDailyDelivery(ready, start, user.bearer()));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(200);
        } finally {
            executor.shutdownNow();
        }

        assertThat(characterColumns(characterId))
                .containsEntry("stat_algorithm", 3)
                .containsEntry("stat_network", 1)
                .containsEntry("battle_power", 4);
        JsonNode storedReport = storedState(user.id()).path("reports").get(0);
        assertThat(storedReport.path("scoreApplied").asBoolean()).isTrue();
        assertStoredCharactersEmpty(user.id());
    }

    @Test
    void deliverDailyReportFailureAndMissingCharacterDoNotMarkScoreApplied() throws Exception {
        AdminTestFixture.ProvisionedUser failureUser = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number failureCharacterId = itemId(createCharacter(failureUser.bearer(), "Fail Daily", "daily keyword", "steady")
                .andExpect(status().isOk())
                .andReturn());
        saveReport(failureUser.bearer(), "Failure report")
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/game/daily-report/deliver")
                        .header("Authorization", failureUser.bearer())
                        .contentType("application/json")
                        .content("""
                                {"fail":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.status").value("failed"))
                .andExpect(jsonPath("$.state.reports[0].scoreApplied").value(false))
                .andExpect(jsonPath("$.state.characters[0].battlePower").value(0));
        assertThat(characterColumns(failureCharacterId)).containsEntry("battle_power", 0);

        AdminTestFixture.ProvisionedUser staleUser = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number staleCharacterId = itemId(createCharacter(staleUser.bearer(), "Stale Daily", "stale keyword", "steady")
                .andExpect(status().isOk())
                .andReturn());
        saveReport(staleUser.bearer(), "Stale report")
                .andExpect(status().isOk());
        deleteCharacterRow(staleCharacterId);

        mockMvc.perform(post("/api/game/daily-report/deliver")
                        .header("Authorization", staleUser.bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.status").value("failed"))
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(nullValue()))
                .andExpect(jsonPath("$.state.reports[0].scoreApplied").value(false))
                .andExpect(jsonPath("$.state.characters.length()").value(0));

        JsonNode storedReport = storedState(staleUser.id()).path("reports").get(0);
        assertThat(storedReport.path("scoreApplied").asBoolean()).isFalse();
        assertStoredCharactersEmpty(staleUser.id());
    }

    @Test
    void activeSwitchAndDeleteRepairFutureGrowthTargetsWithoutRewritingHistory() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number firstId = itemId(createCharacter(user.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn());
        Number secondId = itemId(createCharacter(user.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andReturn());
        saveReport(user.bearer(), "Second active report")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(secondId.toString()));

        activateCharacter(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(firstId))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(secondId.toString()));

        mockMvc.perform(post("/api/game/daily-report/deliver")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(firstId.toString()))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(firstId.toString()))
                .andExpect(jsonPath("$.state.characters[1].stats.algo").value(3));
        assertStoredCharactersEmpty(user.id());

        retryImage(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.spriteSheetUrl").isNotEmpty());
        assertStoredCharactersEmpty(user.id());

        deleteCharacter(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(secondId))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(firstId.toString()));

        JsonNode stored = storedState(user.id());
        assertThat(stored.path("reports").get(0).path("characterId").asText()).isEqualTo(firstId.toString());
        assertThat(stored.path("dailyReport").path("characterId").asLong()).isEqualTo(secondId.longValue());
        for (JsonNode quiz : stored.path("quizzes")) {
            if (!quiz.path("scored").asBoolean(false)) {
                assertThat(quiz.path("characterId").asText()).isNotEqualTo(firstId.toString());
            }
        }
        assertStoredCharactersEmpty(user.id());
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

    private ResultActions saveReport(String bearer, String title) throws Exception {
        return mockMvc.perform(post("/api/game/reports")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("""
                        {"mood":"joy","title":"%s","content":"Daily bridge content","tags":["algo","net"]}
                        """.formatted(title)));
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

    private Callable<Integer> concurrentQuizSubmit(
            CountDownLatch ready,
            CountDownLatch start,
            String bearer,
            String quizId
    ) {
        return () -> {
            awaitConcurrentStart(ready, start);
            return mockMvc.perform(post("/api/game/quizzes/{id}/submit", quizId)
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content("""
                                    {"selected":1}
                                    """))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };
    }

    private Callable<Integer> concurrentDailyDelivery(
            CountDownLatch ready,
            CountDownLatch start,
            String bearer
    ) {
        return () -> {
            awaitConcurrentStart(ready, start);
            return mockMvc.perform(post("/api/game/daily-report/deliver")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content("{}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };
    }

    private void awaitConcurrentStart(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for concurrent start.");
        }
    }

    private Number itemId(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.item.id");
    }

    private Map<String, Object> characterColumns(Number characterId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT stat_algorithm, stat_network, battle_power, emotion, status_message
                        FROM characters
                        WHERE id = ?
                        """,
                characterId.longValue()
        );
    }

    private long characterCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE user_id = ?",
                Long.class,
                userId
        );
        return count == null ? 0 : count;
    }

    private void deleteCharacterRow(Number characterId) {
        jdbcTemplate.update("DELETE FROM characters WHERE id = ?", characterId.longValue());
    }

    private void assertStoredCharactersEmpty(long userId) throws Exception {
        JsonNode characters = storedState(userId).get("characters");
        assertThat(characters).isNotNull();
        assertThat(characters.isArray()).isTrue();
        assertThat(characters.size()).isZero();
    }

    private JsonNode storedQuiz(long userId, String quizId) throws Exception {
        for (JsonNode quiz : storedState(userId).path("quizzes")) {
            if (quizId.equals(quiz.path("id").asText())) {
                return quiz;
            }
        }
        throw new AssertionError("Stored quiz not found: " + quizId);
    }

    private JsonNode storedState(long userId) throws Exception {
        String json = jdbcTemplate.queryForObject(
                "SELECT state_json FROM game_states WHERE user_id = ?",
                String.class,
                userId
        );
        return objectMapper.readTree(json);
    }

    private String uniqueEmail() {
        return "character-bridge-" + UUID.randomUUID() + "@example.com";
    }
}
