package com.commitgotchi.auth;

import com.commitgotchi.auth.domain.RefreshTokenRepository;
import com.commitgotchi.security.JwtConfiguration;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.User;
import com.commitgotchi.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LogoutApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtConfiguration jwtConfiguration;

    @BeforeEach
    void cleanUsers() {
        userRepository.deleteAll();
    }

    @Test
    void logoutDeletesOnlySubmittedActiveSessionAndAccessTokenRemainsValid(CapturedOutput output) throws Exception {
        signup();
        MvcResult firstLogin = login();
        MvcResult secondLogin = login();
        String accessToken = json(firstLogin, "$.accessToken");
        String loggedOutToken = json(firstLogin, "$.refreshToken");
        String otherToken = json(secondLogin, "$.refreshToken");
        long userId = userRepository.findAll().get(0).getId();

        logout(loggedOutToken)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?",
                Integer.class, hash(loggedOutToken)
        )).isZero();
        assertThat(refreshTokenRepository.countByUserIdAndRevokedAtIsNull(userId)).isEqualTo(1);

        refresh(loggedOutToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));
        refresh(otherToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("person@example.com"));

        assertThat(output.getAll())
                .doesNotContain(loggedOutToken)
                .doesNotContain(otherToken)
                .doesNotContain(hash(loggedOutToken));
    }

    @Test
    void logoutIsIdempotentForRepeatedMissingAndMalformedTokens() throws Exception {
        signup();
        String rawToken = json(login(), "$.refreshToken");
        String missingToken = "A".repeat(43);

        logout(rawToken).andExpect(status().isNoContent()).andExpect(content().string(""));
        logout(rawToken).andExpect(status().isNoContent()).andExpect(content().string(""));
        logout(missingToken).andExpect(status().isNoContent()).andExpect(content().string(""));
        logout("short").andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void logoutKeepsRequestDtoValidationContract() throws Exception {
        mockMvc.perform(post("/api/auth/logout").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/auth/logout").contentType("application/json")
                        .content("{\"refreshToken\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/auth/logout").contentType("application/json")
                        .content("{\"refreshToken\":\"short\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/auth/logout").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void logoutDoesNotDeleteRotationEvidenceOrPreventReuseResponse() throws Exception {
        signup();
        String rotatedToken = json(login(), "$.refreshToken");
        String activeToken = json(refresh(rotatedToken).andExpect(status().isOk()).andReturn(), "$.refreshToken");

        logout(rotatedToken).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ? AND revoked_at IS NOT NULL",
                Integer.class, hash(rotatedToken)
        )).isEqualTo(1);
        refresh(rotatedToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REUSED"));
        refresh(activeToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REUSED"));
    }

    @Test
    void invalidOrExpiredAccessTokenHeaderCannotBlockLogout() throws Exception {
        signup();
        MvcResult login = login();
        String firstToken = json(login, "$.refreshToken");
        String secondToken = json(login(), "$.refreshToken");
        User user = userRepository.findAll().get(0);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer invalid")
                        .contentType("application/json")
                        .content(body(firstToken)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + expiredAccessToken(user))
                        .contentType("application/json")
                        .content(body(secondToken)))
                .andExpect(status().isNoContent());
    }

    private void signup() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("{\"email\":\"person@example.com\",\"password\":\"very-secure-password\"}"))
                .andExpect(status().isCreated());
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"person@example.com\",\"password\":\"very-secure-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions logout(String rawToken) throws Exception {
        return mockMvc.perform(post("/api/auth/logout")
                .contentType("application/json")
                .content(body(rawToken)));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String rawToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType("application/json")
                .content(body(rawToken)));
    }

    private String expiredAccessToken(User user) {
        Instant expiresAt = Instant.now().minusSeconds(60);
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                JwtClaimsSet.builder()
                        .issuer(jwtConfiguration.issuer())
                        .subject(Long.toString(user.getId()))
                        .issuedAt(expiresAt.minus(jwtConfiguration.accessTokenTtl()))
                        .expiresAt(expiresAt)
                        .claim("email", user.getEmail())
                        .claim("role", user.getRole().name())
                        .claim("typ", "access")
                        .build()
        )).getTokenValue();
    }

    private String body(String rawToken) {
        return "{\"refreshToken\":\"%s\"}".formatted(rawToken);
    }

    private String json(MvcResult result, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private String hash(String rawToken) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
    }
}
