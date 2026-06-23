package com.commitgotchi.quiz;

import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "commitgotchi.internal-api.secret=test-internal-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuizSubmitFastApiIntegrationTest extends PostgresIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AtomicReference<CapturedFastApiRequest> CAPTURED_REQUEST = new AtomicReference<>();
    private static HttpServer fastApiServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @DynamicPropertySource
    static void quizGradingProperties(DynamicPropertyRegistry registry) {
        startFastApiServer();
        registry.add("commitgotchi.quiz.grading.enabled", () -> "true");
        registry.add("commitgotchi.quiz.grading.base-url",
                () -> "http://127.0.0.1:" + fastApiServer.getAddress().getPort());
        registry.add("commitgotchi.quiz.grading.callback-url",
                () -> "http://springboot:8080/api/internal/quizzes/grade-result");
    }

    @AfterAll
    static void stopFastApiServer() {
        if (fastApiServer != null) {
            fastApiServer.stop(0);
        }
    }

    @BeforeEach
    void cleanDatabase() {
        CAPTURED_REQUEST.set(null);
        userRepository.deleteAll();
    }

    @Test
    void submitQuizRequestsFastApiAndCallbackUpdatesQuizState() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        MvcResult created = mockMvc.perform(post("/api/game/characters")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"name":"Quiz API","keyword":"green study slime","personality":"Kind but precise"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String quizId = JsonPath.read(created.getResponse().getContentAsString(), "$.state.quizzes[0].id");

        MvcResult submitted = mockMvc.perform(post("/api/game/quizzes/{id}/submit", quizId)
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {"userAnswer":"그리디 전제 때문에 음수 간선이 있으면 확정한 최단거리를 다시 갱신하지 못합니다."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.submitted").value(true))
                .andExpect(jsonPath("$.item.grading").value(true))
                .andExpect(jsonPath("$.item.scored").value(false))
                .andExpect(jsonPath("$.item.gradeFailed").value(false))
                .andReturn();

        String submissionId = JsonPath.read(submitted.getResponse().getContentAsString(), "$.item.submissionId");
        CapturedFastApiRequest captured = CAPTURED_REQUEST.get();
        assertThat(captured).isNotNull();
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.path()).isEqualTo("/api/internal/quizzes/grade");
        assertThat(captured.authorization()).isEqualTo("Internal test-internal-secret");
        assertThat(captured.body().path("submissionId").asText()).isEqualTo(submissionId);
        assertThat(captured.body().path("userId").asLong()).isEqualTo(user.id());
        assertThat(captured.body().path("question").asText()).contains("다익스트라");
        assertThat(captured.body().path("userAnswer").asText()).contains("음수 간선");
        assertThat(captured.body().path("scoreAllocation").path("algorithm").asInt()).isEqualTo(10);
        assertThat(captured.body().path("callbackUrl").asText())
                .isEqualTo("http://springboot:8080/api/internal/quizzes/grade-result");

        long fastApiQuizId = captured.body().path("quizId").asLong();
        mockMvc.perform(post("/api/internal/quizzes/grade-result")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "submissionId":"%s",
                                  "userId":%d,
                                  "quizId":%d,
                                  "status":"GRADED",
                                  "scoreAllocation":{"db":0,"algorithm":10,"cs":0,"network":0,"framework":0},
                                  "scoreDelta":{"db":0,"algorithm":2,"cs":0,"network":0,"framework":0},
                                  "feedback":"원인은 맞췄으나 해결책이 조금 부족합니다.",
                                  "emotion":"JOY",
                                  "statusMessage":"좋아요, 핵심은 잡았어요!"
                                }
                                """.formatted(submissionId, user.id(), fastApiQuizId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.quizzes[0].submitted").value(true))
                .andExpect(jsonPath("$.state.quizzes[0].grading").value(false))
                .andExpect(jsonPath("$.state.quizzes[0].scored").value(true))
                .andExpect(jsonPath("$.state.quizzes[0].correct").value(false))
                .andExpect(jsonPath("$.state.quizzes[0].deltaAmount").value(2))
                .andExpect(jsonPath("$.state.quizzes[0].feedback").value("원인은 맞췄으나 해결책이 조금 부족합니다."))
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(2))
                .andExpect(jsonPath("$.state.characters[0].emotion").value("joy"));

        mockMvc.perform(post("/api/internal/quizzes/grade-result")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "submissionId":"%s",
                                  "userId":%d,
                                  "quizId":%d,
                                  "status":"GRADED",
                                  "scoreAllocation":{"db":0,"algorithm":10,"cs":0,"network":0,"framework":0},
                                  "scoreDelta":{"db":0,"algorithm":2,"cs":0,"network":0,"framework":0},
                                  "feedback":"duplicate",
                                  "emotion":"JOY",
                                  "statusMessage":"duplicate"
                                }
                                """.formatted(submissionId, user.id(), fastApiQuizId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(true));

        mockMvc.perform(get("/api/game/state").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.characters[0].stats.algo").value(2));
    }

    private static void startFastApiServer() {
        if (fastApiServer != null) {
            return;
        }
        try {
            fastApiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fastApiServer.createContext("/api/internal/quizzes/grade", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode requestBody = OBJECT_MAPPER.readTree(body);
                CAPTURED_REQUEST.set(new CapturedFastApiRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        requestBody
                ));
                byte[] response = OBJECT_MAPPER.writeValueAsBytes(Map.of(
                        "accepted", true,
                        "submissionId", requestBody.path("submissionId").asText()
                ));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            fastApiServer.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start fake FastAPI server.", exception);
        }
    }

    private String uniqueEmail() {
        return "quiz-submit-" + UUID.randomUUID() + "@example.com";
    }

    private record CapturedFastApiRequest(String method, String path, String authorization, JsonNode body) {
    }
}
