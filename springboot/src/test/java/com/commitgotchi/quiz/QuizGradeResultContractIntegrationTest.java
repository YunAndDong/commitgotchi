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
import static com.commitgotchi.support.RecommendedQuizTestHelper.createRecommendedQuiz;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                                  "characterId":10,
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
                                  "characterId":10,
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
        String quizStateId = createRecommendedQuiz(mockMvc, user.bearer(), user.id(), characterId);
        long quizId = fastApiQuizId(quizStateId);

        mockMvc.perform(post("/api/internal/quizzes/grade-result")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "submissionId":"quiz-submission-growth",
                                  "userId":%d,
                                  "characterId":%d,
                                  "quizId":%d,
                                  "status":"GRADED",
                                  "scoreAllocation":{"db":0,"algorithm":3,"cs":0,"network":0,"framework":0},
                                  "scoreDelta":{"db":0,"algorithm":2,"cs":0,"network":0,"framework":0},
                                  "feedback":"feedback",
                                  "emotion":"JOY",
                                  "statusMessage":"FastAPI hint"
                                }
                                """.formatted(user.id(), characterId, quizId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        assertThat(characterColumns(characterId))
                .containsEntry("stat_algorithm", 2)
                .containsEntry("battle_power", 2)
                .containsEntry("emotion", "JOY")
                .containsEntry("status_message", "FastAPI hint");
    }

    @Test
    void gradeResultMatchesOnlyQuizIdWithCharacterId() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        long firstCharacterId = characterCreationService.create(
                user.id(),
                new CharacterCreateRequest("Quiz Same Id A", "mint quiz pet", "careful")
        ).getId();
        long secondCharacterId = characterCreationService.create(
                user.id(),
                new CharacterCreateRequest("Quiz Same Id B", "blue quiz pet", "careful")
        ).getId();
        String firstQuizStateId = createRecommendedQuiz(mockMvc, user.bearer(), user.id(), firstCharacterId);
        long firstQuizId = fastApiQuizId(firstQuizStateId);
        String secondQuizStateId = createRecommendedQuiz(mockMvc, user.bearer(), user.id(), secondCharacterId);
        long secondQuizId = fastApiQuizId(secondQuizStateId);

        mockMvc.perform(post("/api/internal/quizzes/grade-result")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "submissionId":"quiz-id-with-wrong-character",
                                  "userId":%d,
                                  "characterId":%d,
                                  "quizId":%d,
                                  "status":"GRADED",
                                  "scoreAllocation":{"db":0,"algorithm":10,"cs":0,"network":0,"framework":0},
                                  "scoreDelta":{"db":0,"algorithm":8,"cs":0,"network":0,"framework":0},
                                  "feedback":"quiz id alone must not match",
                                  "emotion":"JOY",
                                  "statusMessage":"FastAPI hint"
                                }
                                """.formatted(user.id(), secondCharacterId, firstQuizId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        assertThat(characterColumns(firstCharacterId))
                .containsEntry("stat_algorithm", 0)
                .containsEntry("battle_power", 0);
        assertThat(characterColumns(secondCharacterId))
                .containsEntry("stat_algorithm", 0)
                .containsEntry("battle_power", 0);

        mockMvc.perform(post("/api/internal/quizzes/grade-result")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "submissionId":"callback-without-stored-submission",
                                  "userId":%d,
                                  "characterId":%d,
                                  "quizId":%d,
                                  "status":"GRADED",
                                  "scoreAllocation":{"db":0,"algorithm":10,"cs":0,"network":0,"framework":0},
                                  "scoreDelta":{"db":0,"algorithm":2,"cs":0,"network":0,"framework":0},
                                  "feedback":"fallback matched by quiz and character",
                                  "emotion":"JOY",
                                  "statusMessage":"FastAPI hint"
                                }
                                """.formatted(user.id(), secondCharacterId, secondQuizId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.quizzes[0].characterId").value(Long.toString(firstCharacterId)))
                .andExpect(jsonPath("$.state.quizzes[0].scored").value(false))
                .andExpect(jsonPath("$.state.quizzes[1].characterId").value(Long.toString(secondCharacterId)))
                .andExpect(jsonPath("$.state.quizzes[1].scored").value(true));

        assertThat(characterColumns(firstCharacterId))
                .containsEntry("stat_algorithm", 0)
                .containsEntry("battle_power", 0);
        assertThat(characterColumns(secondCharacterId))
                .containsEntry("stat_algorithm", 2)
                .containsEntry("battle_power", 2);
    }

    @Test
    void gradeResultDoesNotApplyBySubmissionIdWhenQuizAndCharacterDoNotMatch() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        long firstCharacterId = characterCreationService.create(
                user.id(),
                new CharacterCreateRequest("Quiz Submission A", "mint quiz pet", "careful")
        ).getId();
        long secondCharacterId = characterCreationService.create(
                user.id(),
                new CharacterCreateRequest("Quiz Submission B", "blue quiz pet", "careful")
        ).getId();
        String firstQuizStateId = createRecommendedQuiz(mockMvc, user.bearer(), user.id(), firstCharacterId);
        long firstQuizId = fastApiQuizId(firstQuizStateId);
        String sharedSubmissionId = "shared-submission-id";

        mockMvc.perform(post("/api/internal/quizzes/grade-result")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "submissionId":"%s",
                                  "userId":%d,
                                  "characterId":%d,
                                  "quizId":%d,
                                  "status":"GRADED",
                                  "scoreAllocation":{"db":0,"algorithm":10,"cs":0,"network":0,"framework":0},
                                  "scoreDelta":{"db":0,"algorithm":2,"cs":0,"network":0,"framework":0},
                                  "feedback":"first callback",
                                  "emotion":"JOY",
                                  "statusMessage":"FastAPI hint"
                                }
                                """.formatted(sharedSubmissionId, user.id(), firstCharacterId, firstQuizId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/api/internal/quizzes/grade-result")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "submissionId":"%s",
                                  "userId":%d,
                                  "characterId":%d,
                                  "quizId":999,
                                  "status":"GRADED",
                                  "scoreAllocation":{"db":0,"algorithm":10,"cs":0,"network":0,"framework":0},
                                  "scoreDelta":{"db":0,"algorithm":8,"cs":0,"network":0,"framework":0},
                                  "feedback":"same submission id must not select another quiz",
                                  "emotion":"JOY",
                                  "statusMessage":"FastAPI hint"
                                }
                                """.formatted(sharedSubmissionId, user.id(), secondCharacterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        assertThat(characterColumns(firstCharacterId))
                .containsEntry("stat_algorithm", 2)
                .containsEntry("battle_power", 2);
        assertThat(characterColumns(secondCharacterId))
                .containsEntry("stat_algorithm", 0)
                .containsEntry("battle_power", 0);
    }

    private String uniqueEmail() {
        return "quiz-" + java.util.UUID.randomUUID() + "@example.com";
    }

    private long fastApiQuizId(String stateQuizId) {
        String digits = stateQuizId.replaceAll("\\D+", "");
        assertThat(digits).isNotBlank();
        return Long.parseLong(digits);
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
