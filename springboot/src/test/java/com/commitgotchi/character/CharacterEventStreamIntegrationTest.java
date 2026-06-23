package com.commitgotchi.character;

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
class CharacterEventStreamIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CharacterCreationService characterCreationService;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void ownedCharacterEventStreamStartsAsSse() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter character = createCharacter(user.id(), "Stream");

        MvcResult result = mockMvc.perform(get("/api/game/characters/{id}/events", character.getId())
                        .header("Authorization", user.bearer())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Content-Type", startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)))
                .andReturn();

        result.getRequest().getAsyncContext().complete();
    }

    @Test
    void eventStreamHidesCrossOwnerCharacter() throws Exception {
        AdminTestFixture.ProvisionedUser owner = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser other = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter otherCharacter = createCharacter(other.id(), "Other Stream");

        mockMvc.perform(get("/api/game/characters/{id}/events", otherCharacter.getId())
                        .header("Authorization", owner.bearer())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound());
    }

    private LearningCharacter createCharacter(long userId, String name) {
        return characterCreationService.create(
                userId,
                new CharacterCreateRequest(name, "event stream keyword", "steady")
        );
    }

    private String uniqueEmail() {
        return "events-" + UUID.randomUUID() + "@example.com";
    }
}
