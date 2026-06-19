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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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
class CharacterReadUpdateDeleteApiIntegrationTest extends PostgresIntegrationTest {

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
    void readsOwnedCharacterDetailWithFreshOwnedStateProjection() throws Exception {
        AdminTestFixture.ProvisionedUser first = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser second = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult firstCharacter = createCharacter(first.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn();
        createCharacter(second.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk());

        Number firstCharacterId = JsonPath.read(firstCharacter.getResponse().getContentAsString(), "$.item.id");

        MvcResult detail = mockMvc.perform(get("/api/game/characters/{id}", firstCharacterId)
                        .header("Authorization", first.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(firstCharacterId))
                .andExpect(jsonPath("$.item.name").value("First"))
                .andExpect(jsonPath("$.item.keyword").value("first keyword"))
                .andExpect(jsonPath("$.item.personality").value("first personality"))
                .andExpect(jsonPath("$.item.stats.algo").value(0))
                .andExpect(jsonPath("$.item.stats.cs").value(0))
                .andExpect(jsonPath("$.item.stats.db").value(0))
                .andExpect(jsonPath("$.item.stats.net").value(0))
                .andExpect(jsonPath("$.item.stats.fw").value(0))
                .andExpect(jsonPath("$.item.battlePower").value(0))
                .andExpect(jsonPath("$.item.emotion").value("joy"))
                .andExpect(jsonPath("$.item.isEvolved").value(false))
                .andExpect(jsonPath("$.item.imageStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.item.spriteSheetUrl").value("https://cdn.commitgotchi.local/sprites/fallback-default.png"))
                .andExpect(jsonPath("$.item.spriteMeta.frameMap.baby.joy[0]").value(0))
                .andExpect(jsonPath("$.item.active").value(true))
                .andExpect(jsonPath("$.item.message").value("Ready to learn"))
                .andExpect(jsonPath("$.item.createdAt").isString())
                .andExpect(jsonPath("$.state.characters[0].id").value(firstCharacterId))
                .andReturn();

        List<Map<String, Object>> characters = JsonPath.read(
                detail.getResponse().getContentAsString(),
                "$.state.characters"
        );
        assertThat(characters).hasSize(1);
        assertThat(characters).allSatisfy(character ->
                assertThat(character.get("name")).isEqualTo("First"));
    }

    @Test
    void stateProjectionIncludesBattlePowerEqualToStatsSumAndOwnedOnly() throws Exception {
        AdminTestFixture.ProvisionedUser owner = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser other = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = createCharacter(owner.bearer(), "Owner", "owner keyword", "owner personality")
                .andExpect(status().isOk())
                .andReturn();
        createCharacter(other.bearer(), "Other", "other keyword", "other personality")
                .andExpect(status().isOk());
        String quizId = JsonPath.read(created.getResponse().getContentAsString(), "$.state.quizzes[0].id");

        mockMvc.perform(post("/api/game/quizzes/{id}/submit", quizId)
                        .header("Authorization", owner.bearer())
                        .contentType("application/json")
                        .content("""
                                {"userAnswer":"그리디 전제 때문에 음수 간선이 있으면 확정한 최단거리를 다시 갱신하지 못합니다."}
                                """))
                .andExpect(status().isOk());

        MvcResult state = mockMvc.perform(get("/api/game/state").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].battlePower").value(12))
                .andReturn();

        List<Map<String, Object>> characters = JsonPath.read(
                state.getResponse().getContentAsString(),
                "$.state.characters"
        );
        assertThat(characters).hasSize(1);
        Map<String, Object> character = characters.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Number> stats = (Map<String, Number>) character.get("stats");
        int statsSum = stats.values().stream()
                .mapToInt(Number::intValue)
                .sum();
        assertThat(character.get("battlePower")).isEqualTo(statsSum);
        assertThat(character.get("name")).isEqualTo("Owner");
    }

    @Test
    void characterDetailHidesMissingMalformedAndCrossOwnerIdsAsNotFound() throws Exception {
        AdminTestFixture.ProvisionedUser owner = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser other = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult otherCharacter = createCharacter(other.bearer(), "Other", "other keyword", "other personality")
                .andExpect(status().isOk())
                .andReturn();
        Number otherCharacterId = JsonPath.read(otherCharacter.getResponse().getContentAsString(), "$.item.id");

        mockMvc.perform(get("/api/game/characters/{id}", "not-a-number")
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Authorization"))));

        mockMvc.perform(get("/api/game/characters/{id}", 999_999_999L)
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Authorization"))));

        mockMvc.perform(get("/api/game/characters/{id}", otherCharacterId)
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Authorization"))));
    }

    @Test
    void updateRejectsInvalidAndSystemOwnedFieldsWithoutChangingStoredValues() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = createCharacter(user.bearer(), "Original", "original keyword", "original personality")
                .andExpect(status().isOk())
                .andReturn();
        Number characterId = JsonPath.read(created.getResponse().getContentAsString(), "$.item.id");

        patchCharacter(user.bearer(), characterId, """
                {"name":" ","keyword":"valid keyword","personality":"valid personality"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        patchCharacter(user.bearer(), characterId, """
                {"name":"Valid","keyword":"%s","personality":"valid personality"}
                """.formatted("k".repeat(121)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        patchCharacter(user.bearer(), characterId, """
                {"name":"Valid","keyword":"valid keyword","personality":"valid personality","active":true}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(not(containsString("Authorization"))));

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT name, design_keyword, personality FROM characters WHERE id = ?",
                characterId.longValue()
        );
        assertThat(stored)
                .containsEntry("name", "Original")
                .containsEntry("design_keyword", "original keyword")
                .containsEntry("personality", "original personality");
    }

    @Test
    void updateChangesOnlyEditableFieldsAndReturnsFreshProjection() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = createCharacter(user.bearer(), "Original", "original keyword", "original personality")
                .andExpect(status().isOk())
                .andReturn();
        Number characterId = JsonPath.read(created.getResponse().getContentAsString(), "$.item.id");
        String quizId = JsonPath.read(created.getResponse().getContentAsString(), "$.state.quizzes[0].id");
        mockMvc.perform(post("/api/game/quizzes/{id}/submit", quizId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"userAnswer":"그리디 전제 때문에 음수 간선이 있으면 확정한 최단거리를 다시 갱신하지 못합니다."}
                                """))
                .andExpect(status().isOk());

        Map<String, Object> before = systemOwnedColumns(characterId);

        patchCharacter(user.bearer(), characterId, """
                {"name":"Renamed","keyword":"renamed keyword","personality":"renamed personality"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(characterId))
                .andExpect(jsonPath("$.item.name").value("Renamed"))
                .andExpect(jsonPath("$.item.keyword").value("renamed keyword"))
                .andExpect(jsonPath("$.item.personality").value("renamed personality"))
                .andExpect(jsonPath("$.item.stats.algo").value(12))
                .andExpect(jsonPath("$.item.stats.cs").value(0))
                .andExpect(jsonPath("$.item.stats.db").value(0))
                .andExpect(jsonPath("$.item.stats.net").value(0))
                .andExpect(jsonPath("$.item.stats.fw").value(0))
                .andExpect(jsonPath("$.item.battlePower").value(12))
                .andExpect(jsonPath("$.item.emotion").value("joy"))
                .andExpect(jsonPath("$.item.imageStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.item.spriteSheetUrl").value("https://cdn.commitgotchi.local/sprites/fallback-default.png"))
                .andExpect(jsonPath("$.item.spriteMeta.frameMap.baby.joy[0]").value(0))
                .andExpect(jsonPath("$.item.message").value("좋은 답변이야! 핵심을 잡았어."))
                .andExpect(jsonPath("$.item.active").value(true))
                .andExpect(jsonPath("$.state.characters[0].name").value("Renamed"));

        Map<String, Object> after = systemOwnedColumns(characterId);
        assertThat(after)
                .containsEntry("stat_algorithm", before.get("stat_algorithm"))
                .containsEntry("stat_cs", before.get("stat_cs"))
                .containsEntry("stat_db", before.get("stat_db"))
                .containsEntry("stat_network", before.get("stat_network"))
                .containsEntry("stat_framework", before.get("stat_framework"))
                .containsEntry("battle_power", before.get("battle_power"))
                .containsEntry("is_evolved", before.get("is_evolved"))
                .containsEntry("emotion", before.get("emotion"))
                .containsEntry("status_message", before.get("status_message"))
                .containsEntry("image_status", before.get("image_status"))
                .containsEntry("sprite_meta", before.get("sprite_meta"))
                .containsEntry("is_active", before.get("is_active"))
                .containsEntry("created_at", before.get("created_at"));
    }

    @Test
    void updateHidesMissingMalformedAndCrossOwnerIdsAsNotFound() throws Exception {
        AdminTestFixture.ProvisionedUser owner = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser other = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult otherCharacter = createCharacter(other.bearer(), "Other", "other keyword", "other personality")
                .andExpect(status().isOk())
                .andReturn();
        Number otherCharacterId = JsonPath.read(otherCharacter.getResponse().getContentAsString(), "$.item.id");

        mockMvc.perform(patch("/api/game/characters/{id}", "not-a-number")
                        .header("Authorization", owner.bearer())
                        .contentType("application/json")
                        .content(validUpdateJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Authorization"))));

        mockMvc.perform(patch("/api/game/characters/{id}", 999_999_999L)
                        .header("Authorization", owner.bearer())
                        .contentType("application/json")
                        .content(validUpdateJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Authorization"))));

        patchCharacter(owner.bearer(), otherCharacterId, validUpdateJson())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Authorization"))));
    }

    @Test
    void deletesInactiveCharacterWithoutChangingExistingActiveCharacter() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult first = createCharacter(user.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn();
        MvcResult second = createCharacter(user.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andReturn();
        Number firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.item.id");
        Number secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.item.id");

        deleteCharacter(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(firstId))
                .andExpect(jsonPath("$.item.name").value("First"))
                .andExpect(jsonPath("$.state.characters[0].id").value(secondId))
                .andExpect(jsonPath("$.state.characters[0].active").value(true));

        assertThat(characterRepository.findById(firstId.longValue())).isEmpty();
        assertThat(characterRepository.findById(secondId.longValue())).isPresent();
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
        assertThat(activeCharacterId(user.id())).isEqualTo(secondId.longValue());
    }

    @Test
    void deletingActiveCharacterReassignsNewestRemainingCharacterAndDailyReport() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult first = createCharacter(user.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn();
        MvcResult second = createCharacter(user.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andReturn();
        MvcResult third = createCharacter(user.bearer(), "Third", "third keyword", "third personality")
                .andExpect(status().isOk())
                .andReturn();
        Number firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.item.id");
        Number secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.item.id");
        Number thirdId = JsonPath.read(third.getResponse().getContentAsString(), "$.item.id");

        MvcResult deleted = deleteCharacter(user.bearer(), thirdId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(thirdId))
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(secondId))
                .andReturn();

        List<Map<String, Object>> characters = JsonPath.read(
                deleted.getResponse().getContentAsString(),
                "$.state.characters"
        );
        assertThat(characters).hasSize(2);
        assertThat(characters).extracting(character -> ((Number) character.get("id")).longValue())
                .containsExactly(secondId.longValue(), firstId.longValue());
        assertThat(characters).filteredOn(character -> Boolean.TRUE.equals(character.get("active")))
                .singleElement()
                .satisfies(character -> assertThat(((Number) character.get("id")).longValue())
                        .isEqualTo(secondId.longValue()));

        assertThat(characterRepository.findById(thirdId.longValue())).isEmpty();
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
        assertThat(activeCharacterId(user.id())).isEqualTo(secondId.longValue());
    }

    @Test
    void deletingActiveCharacterWithPendingReportKeepsDeliveryOnNewActiveCharacter() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult first = createCharacter(user.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn();
        MvcResult second = createCharacter(user.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andReturn();
        Number firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.item.id");
        Number secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.item.id");

        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"mood":"joy","title":"Pending active report","content":"Will be reassigned","tags":["algo"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(secondId.toString()));

        deleteCharacter(user.bearer(), secondId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(firstId))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(firstId.toString()));

        mockMvc.perform(post("/api/game/daily-report/deliver")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(firstId.toString()))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(firstId.toString()))
                .andExpect(jsonPath("$.state.characters[0].id").value(firstId))
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(3));

        assertThat(characterRepository.findById(secondId.longValue())).isEmpty();
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
        assertThat(activeCharacterId(user.id())).isEqualTo(firstId.longValue());
    }

    @Test
    void deletingLastCharacterClearsCharactersAndPreservesDailyReportCompatibilityData() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = createCharacter(user.bearer(), "Only", "only keyword", "only personality")
                .andExpect(status().isOk())
                .andReturn();
        Number characterId = JsonPath.read(created.getResponse().getContentAsString(), "$.item.id");

        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"mood":"joy","title":"Daily study","content":"Kept compatibility data","tags":["algo"]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/game/daily-report/deliver")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.status").value("ready"))
                .andExpect(jsonPath("$.state.dailyReport.summary").isNotEmpty())
                .andExpect(jsonPath("$.state.dailyReport.deltas.algo").value(3));

        deleteCharacter(user.bearer(), characterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(characterId))
                .andExpect(jsonPath("$.state.characters").isEmpty())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(nullValue()))
                .andExpect(jsonPath("$.state.dailyReport.status").value("ready"))
                .andExpect(jsonPath("$.state.dailyReport.summary").isNotEmpty())
                .andExpect(jsonPath("$.state.dailyReport.deltas.algo").value(3));

        assertThat(characterRepository.findById(characterId.longValue())).isEmpty();
        assertThat(activeCharacterCount(user.id())).isZero();
    }

    @Test
    void deletingLastCharacterWithPendingReportKeepsDeliveredDailyReportCharacterIdNull() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = createCharacter(user.bearer(), "Only", "only keyword", "only personality")
                .andExpect(status().isOk())
                .andReturn();
        Number characterId = JsonPath.read(created.getResponse().getContentAsString(), "$.item.id");

        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"mood":"joy","title":"Pending final report","content":"No replacement active","tags":["algo"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(characterId.toString()));

        deleteCharacter(user.bearer(), characterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters").isEmpty())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(nullValue()))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(nullValue()));

        mockMvc.perform(post("/api/game/daily-report/deliver")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters").isEmpty())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(nullValue()))
                .andExpect(jsonPath("$.state.reports[0].characterId").value(nullValue()));

        assertThat(characterRepository.findById(characterId.longValue())).isEmpty();
        assertThat(activeCharacterCount(user.id())).isZero();
    }

    @Test
    void deletingInactiveCharacterReferencedByTextDailyReportMovesDailyReportToActiveCharacter() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult first = createCharacter(user.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn();
        Number firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.item.id");

        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"mood":"joy","title":"Before switch","content":"Reference first as text","tags":["algo"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(firstId.toString()));

        MvcResult second = createCharacter(user.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andReturn();
        Number secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.item.id");

        deleteCharacter(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.id").value(firstId))
                .andExpect(jsonPath("$.state.dailyReport.characterId").value(secondId))
                .andExpect(jsonPath("$.state.characters[0].id").value(secondId))
                .andExpect(jsonPath("$.state.characters[0].active").value(true));

        assertThat(characterRepository.findById(firstId.longValue())).isEmpty();
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
        assertThat(activeCharacterId(user.id())).isEqualTo(secondId.longValue());
    }

    @Test
    void deletingCharacterReassignsUnscoredStarterQuizzesToActiveCharacter() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult first = createCharacter(user.bearer(), "First", "first keyword", "first personality")
                .andExpect(status().isOk())
                .andReturn();
        Number firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.item.id");
        String firstQuizId = JsonPath.read(first.getResponse().getContentAsString(), "$.state.quizzes[0].id");

        MvcResult second = createCharacter(user.bearer(), "Second", "second keyword", "second personality")
                .andExpect(status().isOk())
                .andReturn();
        Number secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.item.id");

        deleteCharacter(user.bearer(), firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.quizzes[0].id").value(firstQuizId))
                .andExpect(jsonPath("$.state.quizzes[0].characterId").value(secondId.toString()))
                .andExpect(jsonPath("$.state.characters[0].id").value(secondId))
                .andExpect(jsonPath("$.state.characters[0].active").value(true));

        mockMvc.perform(post("/api/game/quizzes/{id}/submit", firstQuizId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"userAnswer":"그리디 전제 때문에 음수 간선이 있으면 확정한 최단거리를 다시 갱신하지 못합니다."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.characterId").value(secondId.toString()))
                .andExpect(jsonPath("$.state.characters[0].id").value(secondId))
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(12))
                .andExpect(jsonPath("$.state.characters[0].battlePower").value(12));

        assertThat(characterRepository.findById(firstId.longValue())).isEmpty();
        assertThat(activeCharacterCount(user.id())).isEqualTo(1);
        assertThat(activeCharacterId(user.id())).isEqualTo(secondId.longValue());
    }

    @Test
    void boardAndReviewMutationsReturnFreshCharacterProjection() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = createCharacter(user.bearer(), "Owner", "owner keyword", "owner personality")
                .andExpect(status().isOk())
                .andReturn();
        Number characterId = JsonPath.read(created.getResponse().getContentAsString(), "$.item.id");

        MvcResult boardPost = mockMvc.perform(post("/api/game/board-posts")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"desc":"Initial board post"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].id").value(characterId))
                .andReturn();
        String postId = JsonPath.read(boardPost.getResponse().getContentAsString(), "$.item.id");

        mockMvc.perform(patch("/api/game/board-posts/{id}", postId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"desc":"Updated board post"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].id").value(characterId));

        MvcResult review = mockMvc.perform(post("/api/game/board-posts/{postId}/reviews", postId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"stars":5,"text":"Looks good"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].id").value(characterId))
                .andReturn();
        String reviewId = JsonPath.read(review.getResponse().getContentAsString(), "$.item.id");

        mockMvc.perform(patch("/api/game/board-posts/{postId}/reviews/{reviewId}", postId, reviewId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"stars":4,"text":"Still good"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].id").value(characterId));

        mockMvc.perform(delete("/api/game/board-posts/{postId}/reviews/{reviewId}", postId, reviewId)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].id").value(characterId));

        mockMvc.perform(delete("/api/game/board-posts/{id}", postId)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].id").value(characterId));
    }

    @Test
    void deleteHidesMissingMalformedAndCrossOwnerIdsAsNotFound() throws Exception {
        AdminTestFixture.ProvisionedUser owner = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser other = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult otherCharacter = createCharacter(other.bearer(), "Other", "other keyword", "other personality")
                .andExpect(status().isOk())
                .andReturn();
        Number otherCharacterId = JsonPath.read(otherCharacter.getResponse().getContentAsString(), "$.item.id");

        mockMvc.perform(delete("/api/game/characters/{id}", "not-a-number")
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Authorization"))));

        mockMvc.perform(delete("/api/game/characters/{id}", 999_999_999L)
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Authorization"))));

        deleteCharacter(owner.bearer(), otherCharacterId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Authorization"))));
    }

    @Test
    void unauthenticatedCharacterReadUpdateAndDeleteReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/game/characters/{id}", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"));

        mockMvc.perform(patch("/api/game/characters/{id}", 1L)
                        .contentType("application/json")
                        .content(validUpdateJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"));

        mockMvc.perform(delete("/api/game/characters/{id}", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"));
    }

    private org.springframework.test.web.servlet.ResultActions createCharacter(
            String bearer,
            String name,
            String keyword,
            String personality
    ) throws Exception {
        return mockMvc.perform(post("/api/game/characters")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("""
                        {"name":"%s","keyword":"%s","personality":"%s"}
                        """.formatted(name, keyword, personality)));
    }

    private org.springframework.test.web.servlet.ResultActions patchCharacter(
            String bearer,
            Number characterId,
            String json
    ) throws Exception {
        return mockMvc.perform(patch("/api/game/characters/{id}", characterId)
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(json));
    }

    private org.springframework.test.web.servlet.ResultActions deleteCharacter(
            String bearer,
            Number characterId
    ) throws Exception {
        return mockMvc.perform(delete("/api/game/characters/{id}", characterId)
                .header("Authorization", bearer));
    }

    private Map<String, Object> systemOwnedColumns(Number characterId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT stat_algorithm, stat_cs, stat_db, stat_network, stat_framework,
                               battle_power, is_evolved, emotion, status_message, image_status,
                               sprite_meta, is_active, created_at
                        FROM characters
                        WHERE id = ?
                        """,
                characterId.longValue()
        );
    }

    private String validUpdateJson() {
        return """
                {"name":"Valid","keyword":"valid keyword","personality":"valid personality"}
                """;
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
        return "character-rud-" + UUID.randomUUID() + "@example.com";
    }
}
