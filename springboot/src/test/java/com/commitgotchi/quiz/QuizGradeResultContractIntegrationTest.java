package com.commitgotchi.quiz;

import com.commitgotchi.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "commitgotchi.internal-api.secret=test-internal-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuizGradeResultContractIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
                                  "feedback":"원인은 맞췄으나 해결책이 빠졌습니다."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void gradeResultEndpointRejectsFastApiEmotionAndStatusMessageHints() throws Exception {
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
