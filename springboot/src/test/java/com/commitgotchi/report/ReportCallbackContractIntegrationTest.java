package com.commitgotchi.report;

import com.commitgotchi.character.api.dto.CharacterCreateRequest;
import com.commitgotchi.character.application.CharacterCreationService;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "commitgotchi.internal-api.secret=test-internal-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportCallbackContractIntegrationTest extends PostgresIntegrationTest {

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

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void internalAuthAcceptedAndReportScoreDeltaMapsToDbDomainOrder() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter character = createCharacter(user.id());

        mockMvc.perform(post("/api/report")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content(validReportPayload(user.id(), character.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        Map<String, Object> columns = characterColumns(character.getId());
        assertThat(columns)
                .containsEntry("stat_db", 1)
                .containsEntry("stat_algorithm", 2)
                .containsEntry("stat_cs", 3)
                .containsEntry("stat_network", 4)
                .containsEntry("stat_framework", 5)
                .containsEntry("battle_power", 15)
                .containsEntry("emotion", "JOY")
                .containsEntry("status_message", "오늘 학습 기록이 알찼어요.");
    }

    @Test
    void wrongInternalSecretIsRejectedWithoutSecretEcho() throws Exception {
        mockMvc.perform(post("/api/report")
                        .header("Authorization", "Internal wrong-secret")
                        .contentType("application/json")
                        .content(validReportPayload(1L, 1L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_INVALID"))
                .andExpect(content().string(not(containsString("wrong-secret"))));
    }

    @Test
    void reportCallbackRejectsEmotionField() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter character = createCharacter(user.id());

        mockMvc.perform(post("/api/report")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "requestId":"request-with-emotion",
                                  "userId":%d,
                                  "characterId":%d,
                                  "targetDate":"2026-06-19",
                                  "status":"SUCCESS",
                                  "scoreDelta":{"db":1,"algorithm":2,"cs":3,"network":4,"framework":5},
                                  "statusMessage":"Spring should own emotion.",
                                  "emotion":"JOY",
                                  "dailyReport":{"text":"body","feedback":"feedback"},
                                  "nextRecommendation":{"topics":["JPA"],"rationale":"next"},
                                  "recommendedQuizzes":[]
                                }
                                """.formatted(user.id(), character.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private LearningCharacter createCharacter(long userId) {
        return characterCreationService.create(
                userId,
                new CharacterCreateRequest("Reporter", "report keyword", "steady")
        );
    }

    private String validReportPayload(long userId, long characterId) {
        return """
                {
                  "requestId":"report-request-1",
                  "userId":%d,
                  "characterId":%d,
                  "targetDate":"2026-06-19",
                  "status":"SUCCESS",
                  "scoreDelta":{"db":1,"algorithm":2,"cs":3,"network":4,"framework":5},
                  "statusMessage":"오늘 학습 기록이 알찼어요.",
                  "dailyReport":{"text":"body","feedback":"feedback"},
                  "nextRecommendation":{"topics":["JPA"],"rationale":"next"},
                  "recommendedQuizzes":[]
                }
                """.formatted(userId, characterId);
    }

    private Map<String, Object> characterColumns(long characterId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT stat_db, stat_algorithm, stat_cs, stat_network, stat_framework,
                               battle_power, emotion, status_message
                        FROM user_character
                        WHERE id = ?
                        """,
                characterId
        );
    }

    private String uniqueEmail() {
        return "report-" + UUID.randomUUID() + "@example.com";
    }
}
