package com.commitgotchi.quiz;

import com.commitgotchi.character.api.dto.CharacterCreateRequest;
import com.commitgotchi.character.application.CharacterCreationService;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.support.AdminTestFixture;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "commitgotchi.internal-api.secret=test-internal-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuizGradeResultContractIntegrationTest extends PostgresIntegrationTest {

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
    void gradeResultEndpointAcceptsOfficialInternalContract() throws Exception {
        mockMvc.perform(post("/api/internal/quizzes/grade-result")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "submissionId":"quiz-submission-1",
                                  "userId":42,
                                  "quizId":55,
                                  "status":"GRADED",
                                  "scoreAllocation":{"db":0,"algorithm":3,"cs":0,"network":0,"framework":0},
                                  "scoreDelta":{"db":0,"algorithm":2,"cs":0,"network":0,"framework":0},
                                  "feedback":"원인은 맞췄으나 해결책이 빠졌습니다.",
                                  "emotion":"JOY",
                                  "statusMessage":"좋아요, 핵심은 잡았어요!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void gradeResultEndpointAcceptsFastApiEmotionAndStatusMessage() throws Exception {
        mockMvc.perform(post("/api/internal/quizzes/grade-result")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "submissionId":"quiz-submission-2",
                                  "userId":42,
                                  "quizId":55,
                                  "status":"GRADED",
                                  "scoreAllocation":{"db":0,"algorithm":3,"cs":0,"network":0,"framework":0},
                                  "scoreDelta":{"db":0,"algorithm":2,"cs":0,"network":0,"framework":0},
                                  "feedback":"feedback",
                                  "emotion":"JOY",
                                  "statusMessage":"FastAPI hint"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void gradeResultEndpointAppliesGrowthToTargetCharacter() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        long characterId = characterCreationService.create(
                user.id(),
                new CharacterCreateRequest("Quiz Growth", "mint quiz pet", "careful")
        ).getId();

        mockMvc.perform(post("/api/internal/quizzes/grade-result")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "submissionId":"quiz-submission-growth",
                                  "userId":%d,
                                  "characterId":%d,
                                  "quizId":55,
                                  "status":"GRADED",
                                  "scoreAllocation":{"db":0,"algorithm":3,"cs":0,"network":0,"framework":0},
                                  "scoreDelta":{"db":0,"algorithm":2,"cs":0,"network":0,"framework":0},
                                  "feedback":"feedback",
                                  "emotion":"JOY",
                                  "statusMessage":"FastAPI hint"
                                }
                                """.formatted(user.id(), characterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        assertThat(characterColumns(characterId))
                .containsEntry("stat_algorithm", 2)
                .containsEntry("battle_power", 2)
                .containsEntry("emotion", "JOY")
                .containsEntry("status_message", "FastAPI hint");
    }

    private String uniqueEmail() {
        return "quiz-" + java.util.UUID.randomUUID() + "@example.com";
    }

    private Map<String, Object> characterColumns(long characterId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT stat_algorithm, battle_power, emotion, status_message
                        FROM user_character
                        WHERE id = ?
                        """,
                characterId
        );
    }
}
