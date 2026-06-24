package com.commitgotchi.character;

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
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CharacterActivationApiIntegrationTest extends PostgresIntegrationTest {

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
    void activatesOwnedInactiveCharacterAndReturnsFreshOwnedProjection() throws Exception {
        AdminTestFixture.ProvisionedUser owner = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser other = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number firstId = itemId(createCharacter(owner.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn());
        Number secondId = itemId(createCharacter(owner.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andReturn());
        createCharacter(other.bearer(), "Other", "other keyword", "other personality")
                .andExpect(status().isOk());

        MvcResult activated = activateCharacter(owner.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(firstId))
                .andExpect(jsonPath("$.item.active").value(true))
                .andReturn();

        List<Map<String, Object>> characters = JsonPath.read(
                activated.getResponse().getContentAsString(),
                "$.state.characters"
        );
        assertThat(characters).hasSize(2);
        assertThat(characters).extracting(character -> character.get("name"))
                .containsExactly("Second", "First");
        assertThat(characters).filteredOn(character -> Boolean.TRUE.equals(character.get("active")))
                .singleElement()
                .satisfies(character -> assertThat(((Number) character.get("id")).longValue())
                        .isEqualTo(firstId.longValue()));

        assertThat(activeCharacterCount(owner.id())).isEqualTo(1);
        assertThat(activeCharacterId(owner.id())).isEqualTo(firstId.longValue());
        assertThat(isActive(secondId)).isFalse();
        assertThat(characterRepository.countByUserId(other.id())).isEqualTo(1);
    }

    @Test
    void activatingAlreadyActiveCharacterIsIdempotentAndPreservesCharacterFields() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = createCharacter(user.bearer(), "Solo", "solo keyword", "solo personality")
                .andExpect(status().isOk())
                .andReturn();
        Number characterId = itemId(created);
        String quizId = JsonPath.read(created.getResponse().getContentAsString(), "$.state.quizzes[0].id");

        mockMvc.perform(post("/api/game/quizzes/{id}/submit", quizId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"userAnswer":"그리디 전제 때문에 음수 간선이 있으면 확정한 최단거리를 다시 갱신하지 못합니다."}
                                """))
                .andExpect(status().isOk());
        Map<String, Object> before = stableCharacterColumns(characterId);

        activateCharacter(user.bearer(), characterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(characterId))
                .andExpect(jsonPath("$.item.active").value(true));

        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
        assertThat(activeCharacterId(user.id())).isEqualTo(characterId.longValue());
        assertThat(stableCharacterColumns(characterId)).isEqualTo(before);
    }

    @Test
    void activationHidesMalformedMissingAndCrossOwnerIdsAsNotFound() throws Exception {
        AdminTestFixture.ProvisionedUser owner = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser other = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number otherCharacterId = itemId(createCharacter(other.bearer(), "Other", "other keyword", "other personality")
                .andExpect(status().isOk())
                .andReturn());

        expectNotFoundWithoutSensitiveDetails(activateCharacter(owner.bearer(), "not-a-number"));
        expectNotFoundWithoutSensitiveDetails(activateCharacter(owner.bearer(), 999_999_999L));
        expectNotFoundWithoutSensitiveDetails(activateCharacter(owner.bearer(), otherCharacterId));
    }

    @Test
    void unauthenticatedActivationReturnsUnauthorizedThroughSecurityFilterChain() throws Exception {
        mockMvc.perform(patch("/api/game/characters/{id}/active", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"));
    }

    @Test
    void activeSwitchReplacesDailyReportSlotAndDailyBridgeUsesNewActiveCharacter() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number firstId = itemId(createCharacter(user.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn());
        Number secondId = itemId(createCharacter(user.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"mood":"joy","title":"Second active report","content":"Before switch","tags":["algo"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(secondId.toString()));

        activateCharacter(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(firstId))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(secondId.toString()));

        mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(firstId));

        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"mood":"joy","title":"First active report","content":"After switch","tags":["algo"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(firstId.toString()))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(firstId.toString()))
                .andExpect(jsonPath("$.state.reports[1].characterId").value(secondId.toString()));

        mockMvc.perform(post("/api/game/daily-report/deliver")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(firstId.toString()))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(firstId.toString()))
                .andExpect(jsonPath("$.state.characters[1].id").value(firstId))
                .andExpect(jsonPath("$.state.characters[1].active").value(true))
                .andExpect(jsonPath("$.state.characters[1].stats.algo").value(3));
    }

    @Test
    void deliveringPreSwitchPendingReportAfterActivationUsesNewActiveCharacter() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        Number firstId = itemId(createCharacter(user.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn());
        Number secondId = itemId(createCharacter(user.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"mood":"joy","title":"Second active report","content":"Before switch","tags":["algo"]}
                                """))
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
                .andExpect(jsonPath("$.state.characters[1].id").value(firstId))
                .andExpect(jsonPath("$.state.characters[1].active").value(true))
                .andExpect(jsonPath("$.state.characters[1].stats.algo").value(3))
                .andExpect(jsonPath("$.state.characters[0].id").value(secondId))
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(0));
    }

    @Test
    void concurrentActivationLeavesExactlyOneRequestedActiveCharacter() throws Exception {
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

        MvcResult state = mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> characters = JsonPath.read(
                state.getResponse().getContentAsString(),
                "$.state.characters"
        );
        assertThat(characters).filteredOn(character -> Boolean.TRUE.equals(character.get("active")))
                .singleElement()
                .satisfies(character -> assertThat(requestedIds)
                        .contains(((Number) character.get("id")).longValue()));
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

    private ResultActions activateCharacter(String bearer, Number characterId) throws Exception {
        return mockMvc.perform(patch("/api/game/characters/{id}/active", characterId)
                .header("Authorization", bearer));
    }

    private ResultActions activateCharacter(String bearer, String characterId) throws Exception {
        return mockMvc.perform(patch("/api/game/characters/{id}/active", characterId)
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
                .andExpect(content().string(not(containsString("DataIntegrityViolationException"))));
    }

    private Number itemId(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.item.id");
    }

    private Map<String, Object> stableCharacterColumns(Number characterId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT name, design_keyword, personality,
                               stat_algorithm, stat_cs, stat_db, stat_network, stat_framework,
                               battle_power, is_evolved, emotion, image_status, status_message, is_active
                        FROM user_character uc
                        JOIN characters catalog ON catalog.id = uc.character_id
                        WHERE uc.id = ?
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

    private long activeCharacterId(long userId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM user_character WHERE user_id = ? AND is_active = true AND deleted_at IS NULL",
                Long.class,
                userId
        );
        return id == null ? 0 : id;
    }

    private boolean isActive(Number characterId) {
        Boolean active = jdbcTemplate.queryForObject(
                "SELECT is_active FROM user_character WHERE id = ?",
                Boolean.class,
                characterId.longValue()
        );
        return Boolean.TRUE.equals(active);
    }

    private String uniqueEmail() {
        return "character-activation-" + UUID.randomUUID() + "@example.com";
    }
}
