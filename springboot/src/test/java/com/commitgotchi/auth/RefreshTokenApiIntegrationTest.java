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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class RefreshTokenApiIntegrationTest extends PostgresIntegrationTest {

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
    void loginStoresOnlyHashAndRotationRevokesOldToken(CapturedOutput output) throws Exception {
        signup();
        String oldToken = loginRefreshToken();
        long userId = userRepository.findAll().get(0).getId();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT token_hash FROM refresh_tokens WHERE user_id = ?", String.class, userId
        )).isEqualTo(hash(oldToken)).isNotEqualTo(oldToken);

        MvcResult rotation = refresh(oldToken);
        assertThat(rotation.getResponse().getStatus()).isEqualTo(200);
        String newToken = json(rotation, "$.refreshToken");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NOT NULL",
                Integer.class, userId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND token_hash = ?",
                Integer.class, userId, hash(newToken)
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?",
                Integer.class, oldToken
        )).isZero();

        assertThat(output.getAll())
                .doesNotContain(oldToken)
                .doesNotContain(newToken)
                .doesNotContain("Authorization:")
                .doesNotContain("JWT_SECRET_BASE64");
    }

    @Test
    void reusedTokenRevokesAllActiveTokensInIndependentTransaction() throws Exception {
        signup();
        String oldToken = loginRefreshToken();
        String newToken = json(refresh(oldToken), "$.refreshToken");
        long userId = userRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content(body(oldToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REUSED"));

        assertThat(refreshTokenRepository.countByUserIdAndRevokedAtIsNull(userId)).isZero();
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content(body(newToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REUSED"));
    }

    @Test
    void malformedMissingUnknownExpiredAndTamperedTokensUseSafeClassification(CapturedOutput output) throws Exception {
        signup();
        String rawToken = loginRefreshToken();
        String tampered = rawToken.substring(0, 42) + (rawToken.endsWith("A") ? "B" : "A");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"short\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"short\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content(body(tampered)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(tampered))));

        jdbcTemplate.update("""
                UPDATE refresh_tokens
                SET created_at = CURRENT_TIMESTAMP - interval '31 days',
                    expires_at = CURRENT_TIMESTAMP - interval '1 day'
                WHERE token_hash = ?
                """, hash(rawToken));
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content(body(rawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));

        assertThat(output.getAll())
                .doesNotContain(rawToken)
                .doesNotContain(tampered)
                .doesNotContain("Authorization:")
                .doesNotContain("JWT_SECRET_BASE64")
                .doesNotContain("at com.commitgotchi.auth");
    }

    @Test
    void concurrentRotationSerializesToOneSuccessOneReuseAndNoActiveTokens() throws Exception {
        signup();
        String rawToken = loginRefreshToken();
        long userId = userRepository.findAll().get(0).getId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MvcResult> first = executor.submit(() -> concurrentRefresh(rawToken, ready, start));
            Future<MvcResult> second = executor.submit(() -> concurrentRefresh(rawToken, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MvcResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(200, 401);
            assertThat(results.stream()
                    .filter(result -> result.getResponse().getStatus() == 401)
                    .findFirst().orElseThrow().getResponse().getContentAsString())
                    .contains("\"code\":\"AUTH_REFRESH_TOKEN_REUSED\"");
            assertThat(refreshTokenRepository.countByUserIdAndRevokedAtIsNull(userId)).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void refreshSucceedsWhenExpiredAccessTokenIsSentInAuthorizationHeader() throws Exception {
        signup();
        String refreshToken = loginRefreshToken();
        User user = userRepository.findAll().get(0);
        String expiredAccessToken = expiredAccessToken(user.getId(), user.getEmail());

        MvcResult rotation = mockMvc.perform(post("/api/auth/refresh")
                        .header("Authorization", "Bearer " + expiredAccessToken)
                        .contentType("application/json")
                        .content(body(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        String newToken = json(rotation, "$.refreshToken");
        assertThat(newToken).isNotEqualTo(refreshToken);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ? AND revoked_at IS NOT NULL",
                Integer.class, hash(refreshToken)
        )).isEqualTo(1);
    }

    private String expiredAccessToken(long userId, String email) {
        Instant expiresAt = Instant.now().minusSeconds(60);
        Instant issuedAt = expiresAt.minus(jwtConfiguration.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtConfiguration.issuer())
                .subject(Long.toString(userId))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("email", email)
                .claim("role", "USER")
                .claim("typ", "access")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
    }

    private MvcResult concurrentRefresh(String rawToken, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        return refresh(rawToken);
    }

    private void signup() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"person@example.com","password":"very-secure-password"}
                                """))
                .andExpect(status().isCreated());
    }

    private String loginRefreshToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"person@example.com","password":"very-secure-password"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return json(result, "$.refreshToken");
    }

    private MvcResult refresh(String rawToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content(body(rawToken)))
                .andReturn();
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
