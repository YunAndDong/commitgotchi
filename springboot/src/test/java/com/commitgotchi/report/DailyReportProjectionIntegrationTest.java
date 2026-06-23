package com.commitgotchi.report;

import com.commitgotchi.character.api.dto.CharacterCreateRequest;
import com.commitgotchi.character.application.CharacterCreationService;
import com.commitgotchi.character.domain.LearningCharacter;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "commitgotchi.internal-api.secret=test-internal-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DailyReportProjectionIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CharacterCreationService characterCreationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void gameStateProjectsReadyDailyReportAndRecommendedQuizzesFromCallback() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter character = createCharacter(user.id(), "Projection");
        MvcResult saved = saveReport(user.bearer(), "Projection report", "Studied projections.", "[\"algo\"]")
                .andReturn();
        String requestId = JsonPath.read(saved.getResponse().getContentAsString(), "$.item.requestId");
        String targetDate = JsonPath.read(saved.getResponse().getContentAsString(), "$.item.date");

        mockMvc.perform(post("/api/report")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content(successPayload(requestId, user.id(), character.getId(), targetDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(get("/api/game/state")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.status").value("ready"))
                .andExpect(jsonPath("$.state.dailyReport.summary").value("body"))
                .andExpect(jsonPath("$.state.dailyReport.feedback").value("feedback"))
                .andExpect(jsonPath("$.state.dailyReport.deltas.algo").value(2))
                .andExpect(jsonPath("$.state.dailyReport.nextRecommendation.topics[0]").value("JPA"))
                .andExpect(jsonPath("$.state.dailyReport.recommendedQuizIds[0]", notNullValue()))
                .andExpect(jsonPath("$.state.reports[0].status").value("reflected"))
                .andExpect(jsonPath("$.state.reports[0].requestId").value(requestId))
                .andExpect(jsonPath("$.state.quizzes[0].sourceReportRequestId").value(requestId));

        JsonNode persisted = persistedState(user.id());
        assertThat(persisted.path("characters")).isEmpty();
        assertThat(persisted.path("dailyReport").path("status").asText()).isEqualTo("ready");
    }

    @Test
    void fallbackProjectionKeepsZeroDeltasAndDoesNotApplyGrowth() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter character = createCharacter(user.id(), "Fallback");
        MvcResult saved = saveReport(user.bearer(), "Fallback report", "Studied fallback.", "[\"db\"]")
                .andReturn();
        String requestId = JsonPath.read(saved.getResponse().getContentAsString(), "$.item.requestId");
        String targetDate = JsonPath.read(saved.getResponse().getContentAsString(), "$.item.date");

        mockMvc.perform(post("/api/report")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content(fallbackPayload(requestId, user.id(), character.getId(), targetDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(get("/api/game/state")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.status").value("fallback"))
                .andExpect(jsonPath("$.state.dailyReport.message", containsString("기본 리포트")))
                .andExpect(jsonPath("$.state.dailyReport.deltas.algo").value(0))
                .andExpect(jsonPath("$.state.dailyReport.deltas.db").value(0));

        Integer battlePower = jdbcTemplate.queryForObject(
                "SELECT battle_power FROM characters WHERE id = ?",
                Integer.class,
                character.getId()
        );
        assertThat(battlePower).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions saveReport(
            String bearer,
            String title,
            String content,
            String tagsJson
    ) throws Exception {
        return mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content("""
                                {
                                  "mood": "joy",
                                  "title": "%s",
                                  "content": "%s",
                                  "tags": %s
                                }
                                """.formatted(title, content, tagsJson)))
                .andExpect(status().isOk());
    }

    private LearningCharacter createCharacter(long userId, String name) {
        return characterCreationService.create(
                userId,
                new CharacterCreateRequest(name, "projection keyword", "steady")
        );
    }

    private String successPayload(String requestId, long userId, long characterId, String targetDate) {
        return """
                {
                  "requestId":"%s",
                  "userId":%d,
                  "characterId":%d,
                  "targetDate":"%s",
                  "status":"SUCCESS",
                  "scoreDelta":{"db":1,"algorithm":2,"cs":0,"network":0,"framework":0},
                  "statusMessage":"오늘 학습 기록이 알찼어요.",
                  "dailyReport":{"text":"body","feedback":"feedback"},
                  "nextRecommendation":{"topics":["JPA"],"rationale":"next"},
                  "recommendedQuizzes":[{
                    "problemId":77,
                    "question":"JPA N+1을 설명해 주세요.",
                    "modelAnswer":"연관 로딩 전략을 점검합니다.",
                    "scoreAllocation":{"db":1,"algorithm":0,"cs":0,"network":0,"framework":2}
                  }]
                }
                """.formatted(requestId, userId, characterId, targetDate);
    }

    private String fallbackPayload(String requestId, long userId, long characterId, String targetDate) {
        return """
                {
                  "requestId":"%s",
                  "userId":%d,
                  "characterId":%d,
                  "targetDate":"%s",
                  "status":"FALLBACK",
                  "scoreDelta":{"db":5,"algorithm":5,"cs":0,"network":0,"framework":0},
                  "statusMessage":"분석이 지연되어 기본 피드백을 제공해요.",
                  "dailyReport":{"text":"fallback body","feedback":"fallback feedback"},
                  "nextRecommendation":{"topics":["복습"],"rationale":"fallback"},
                  "recommendedQuizzes":[]
                }
                """.formatted(requestId, userId, characterId, targetDate);
    }

    private JsonNode persistedState(long userId) throws Exception {
        String json = jdbcTemplate.queryForObject(
                "SELECT state_json FROM game_states WHERE user_id = ?",
                String.class,
                userId
        );
        return objectMapper.readTree(json);
    }

    private String uniqueEmail() {
        return "projection-" + UUID.randomUUID() + "@example.com";
    }
}
