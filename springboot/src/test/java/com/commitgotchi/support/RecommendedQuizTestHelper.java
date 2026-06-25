package com.commitgotchi.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public final class RecommendedQuizTestHelper {

    public static final String INTERNAL_SECRET = "test-internal-secret";
    public static final String INTERNAL_AUTH = "Internal " + INTERNAL_SECRET;
    public static final String MODEL_ANSWER = "다익스트라는 한 번 확정한 최단거리를 다시 갱신하지 않는 그리디 전제에 기대기 때문에 음수 간선을 처리하지 못합니다.";

    private RecommendedQuizTestHelper() {
    }

    public static String createRecommendedQuiz(
            MockMvc mockMvc,
            String bearer,
            long userId,
            long characterId
    ) throws Exception {
        return createRecommendedQuiz(mockMvc, bearer, userId, characterId, LocalDate.now().minusDays(1));
    }

    public static String createRecommendedQuiz(
            MockMvc mockMvc,
            String bearer,
            long userId,
            long characterId,
            LocalDate targetDate
    ) throws Exception {
        String requestId = "test-quiz-" + UUID.randomUUID();
        mockMvc.perform(post("/api/report")
                        .header("Authorization", INTERNAL_AUTH)
                        .contentType("application/json")
                        .content("""
                                {
                                  "requestId":"%s",
                                  "userId":%d,
                                  "characterId":%d,
                                  "targetDate":"%s",
                                  "status":"SUCCESS",
                                  "scoreDelta":{"db":0,"algorithm":0,"cs":0,"network":0,"framework":0},
                                  "statusMessage":"추천 퀴즈가 준비됐어요.",
                                  "dailyReport":{"text":"quiz seed","feedback":"quiz seed"},
                                  "nextRecommendation":{"topics":["algorithm"],"rationale":"quiz seed"},
                                  "recommendedQuizzes":[{
                                    "problemId":101,
                                    "question":"다익스트라 알고리즘이 음의 가중치 간선을 처리하지 못하는 이유를 설명해 주세요.",
                                    "modelAnswer":"%s",
                                    "scoreAllocation":{"db":0,"algorithm":10,"cs":0,"network":0,"framework":0}
                                  }]
                                }
                                """.formatted(requestId, userId, characterId, targetDate, MODEL_ANSWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        MvcResult state = mockMvc.perform(get("/api/game/state").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(state.getResponse().getContentAsString(), "$.state.dailyReport.recommendedQuizIds[0]");
    }
}
