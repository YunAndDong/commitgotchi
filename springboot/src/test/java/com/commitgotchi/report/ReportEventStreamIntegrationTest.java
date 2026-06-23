package com.commitgotchi.report;

import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportEventStreamIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void reportEventStreamStartsAsSseForAuthenticatedUser() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");

        MvcResult result = mockMvc.perform(get("/api/game/reports/events")
                        .header("Authorization", user.bearer())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Content-Type", startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)))
                .andReturn();

        result.getRequest().getAsyncContext().complete();
    }

    @Test
    void reportEventStreamRejectsMissingAuthentication() throws Exception {
        mockMvc.perform(get("/api/game/reports/events")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }

    private String uniqueEmail() {
        return "report-events-" + UUID.randomUUID() + "@example.com";
    }
}
