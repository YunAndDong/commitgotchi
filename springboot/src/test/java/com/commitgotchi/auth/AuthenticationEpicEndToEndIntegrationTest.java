package com.commitgotchi.auth;

import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationEpicEndToEndIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanUsers() {
        userRepository.deleteAll();
    }

    @Test
    void signupLoginCurrentUserRotationLogoutAndRejectedRefreshCompleteEpicFlow() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("{\"email\":\"person@example.com\",\"password\":\"very-secure-password\"}"))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"person@example.com\",\"password\":\"very-secure-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String firstAccessToken = json(login, "$.accessToken");
        String firstRefreshToken = json(login, "$.refreshToken");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + firstAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("person@example.com"));

        MvcResult rotation = mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content(body(firstRefreshToken)))
                .andExpect(status().isOk())
                .andReturn();
        String rotatedAccessToken = json(rotation, "$.accessToken");
        String rotatedRefreshToken = json(rotation, "$.refreshToken");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType("application/json")
                        .content(body(rotatedRefreshToken)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content(body(rotatedRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + rotatedAccessToken))
                .andExpect(status().isOk());
    }

    private String body(String refreshToken) {
        return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
    }

    private String json(MvcResult result, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
    }
}
