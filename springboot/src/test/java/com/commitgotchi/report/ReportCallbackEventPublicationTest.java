package com.commitgotchi.report;

import com.commitgotchi.character.api.dto.CharacterCreateRequest;
import com.commitgotchi.character.application.CharacterCreationService;
import com.commitgotchi.character.application.CharacterEventService;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.report.application.ReportEventService;
import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "commitgotchi.internal-api.secret=test-internal-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportCallbackEventPublicationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CharacterCreationService characterCreationService;

    @MockBean
    private ReportEventService reportEventService;

    @MockBean
    private CharacterEventService characterEventService;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void reportCallbackPublishesReadyEventOnceAfterDuplicateCallback() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter character = characterCreationService.create(
                user.id(),
                new CharacterCreateRequest("Event Callback", "event keyword", "steady")
        );
        MvcResult saved = mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", user.bearer())
                        .contentType("application/json")
                        .content("""
                                {
                                  "mood": "joy",
                                  "title": "Event report",
                                  "content": "Studied events.",
                                  "tags": ["algo"]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String requestId = JsonPath.read(saved.getResponse().getContentAsString(), "$.item.requestId");
        String targetDate = JsonPath.read(saved.getResponse().getContentAsString(), "$.item.date");
        String payload = payload(requestId, user.id(), character.getId(), targetDate);

        mockMvc.perform(post("/api/report")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
        mockMvc.perform(post("/api/report")
                        .header("Authorization", "Internal test-internal-secret")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        verify(reportEventService, times(1)).publishReportReadyAfterCommit(user.id());
        verify(characterEventService, times(1)).publishCharacterUpdatedAfterCommit(eq(user.id()), any(LearningCharacter.class));
    }

    private String payload(String requestId, long userId, long characterId, String targetDate) {
        return """
                {
                  "requestId":"%s",
                  "userId":%d,
                  "characterId":%d,
                  "targetDate":"%s",
                  "status":"SUCCESS",
                  "scoreDelta":{"db":0,"algorithm":1,"cs":0,"network":0,"framework":0},
                  "statusMessage":"이벤트 테스트",
                  "dailyReport":{"text":"body","feedback":"feedback"},
                  "nextRecommendation":{"topics":["events"],"rationale":"next"},
                  "recommendedQuizzes":[]
                }
                """.formatted(requestId, userId, characterId, targetDate);
    }

    private String uniqueEmail() {
        return "callback-events-" + UUID.randomUUID() + "@example.com";
    }
}
