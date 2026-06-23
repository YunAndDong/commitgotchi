package com.commitgotchi.auth;

import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.mock.web.MockCookie;

import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LoginAndCurrentUserIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUsers() {
        userRepository.deleteAll();
    }

    @Test
    void signupLoginAndCurrentUserCompleteEndToEndFlow() throws Exception {
        signup();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"  PERSON@EXAMPLE.COM ","password":"very-secure-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.accessTokenExpiresAt").isString())
                .andExpect(jsonPath("$.refreshToken").value(org.hamcrest.Matchers.matchesPattern("[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.refreshTokenExpiresAt").isString())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("cg_refresh="),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax")
                )))
                .andReturn();

        String accessToken = com.jayway.jsonpath.JsonPath.read(
                login.getResponse().getContentAsString(),
                "$.accessToken"
        );
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("person@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String refreshToken = com.jayway.jsonpath.JsonPath.read(
                login.getResponse().getContentAsString(), "$.refreshToken");
        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        String refreshedAccessToken = com.jayway.jsonpath.JsonPath.read(
                refreshed.getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + refreshedAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("person@example.com"));
    }

    @Test
    void refreshAndLogoutSupportHttpOnlyCookieFlow() throws Exception {
        signup();
        MvcResult login = login("person@example.com", "very-secure-password");
        String refreshToken = com.jayway.jsonpath.JsonPath.read(
                login.getResponse().getContentAsString(), "$.refreshToken");

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh-cookie")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .cookie(new MockCookie("cg_refresh", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("cg_refresh=")))
                .andReturn();
        String rotated = com.jayway.jsonpath.JsonPath.read(
                refreshed.getResponse().getContentAsString(), "$.refreshToken");

        mockMvc.perform(post("/api/auth/logout-cookie")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .cookie(new MockCookie("cg_refresh", rotated)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        mockMvc.perform(post("/api/auth/refresh-cookie")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .cookie(new MockCookie("cg_refresh", rotated)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void softDeletedUserCannotLoginRefreshOrUseProtectedApi() throws Exception {
        signup();
        MvcResult login = login("person@example.com", "very-secure-password");
        String accessToken = com.jayway.jsonpath.JsonPath.read(
                login.getResponse().getContentAsString(),
                "$.accessToken"
        );
        String refreshToken = com.jayway.jsonpath.JsonPath.read(
                login.getResponse().getContentAsString(),
                "$.refreshToken"
        );

        jdbcTemplate.update("""
                UPDATE users
                SET deleted_at = CURRENT_TIMESTAMP
                WHERE email = 'person@example.com'
                """);

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"person@example.com","password":"very-secure-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_INVALID"));
    }

    @Test
    void unknownEmailAndWrongPasswordReturnSameSafeFailure() throws Exception {
        signup();

        MvcResult unknown = login("unknown@example.com", "very-secure-password");
        MvcResult wrongPassword = login("person@example.com", "wrong-password");

        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getContentAsString()).contains("\"code\":\"AUTH_INVALID_CREDENTIALS\"");
        assertThat(wrongPassword.getResponse().getContentAsString()).contains("\"code\":\"AUTH_INVALID_CREDENTIALS\"");
        assertThat(unknown.getResponse().getContentAsString()).doesNotContain("unknown@example.com");
        assertThat(wrongPassword.getResponse().getContentAsString()).doesNotContain("wrong-password");
    }

    @Test
    void missingMalformedAndTamperedTokensUseDistinctSafeCodes() throws Exception {
        signup();
        String accessToken = accessToken();
        int signatureStart = accessToken.lastIndexOf('.') + 1;
        char firstSignatureCharacter = accessToken.charAt(signatureStart);
        char replacement = firstSignatureCharacter == 'A' ? 'B' : 'A';
        String tampered = accessToken.substring(0, signatureStart)
                + replacement
                + accessToken.substring(signatureStart + 1);

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mockMvc.perform(get("/api/users/me").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_INVALID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Basic abc"))));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_INVALID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(tampered))));
    }

    @Test
    void publicPathsAllowMissingTokenAndOtherApiPathsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mockMvc.perform(get("/api/health").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_INVALID"));
        mockMvc.perform(get("/api/not-yet-implemented"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"));
    }

    @Test
    void bearerAuthenticationSchemeIsCaseInsensitive() throws Exception {
        signup();

        mockMvc.perform(get("/api/users/me").header("Authorization", "bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("person@example.com"));
    }

    @Test
    void expiredTokenReturnsDedicatedSafeCode() throws Exception {
        Instant now = Instant.now();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                JwtClaimsSet.builder()
                        .issuer("commitgotchi-springboot")
                        .subject("42")
                        .issuedAt(now.minusSeconds(901))
                        .expiresAt(now.minusSeconds(1))
                        .claim("email", "person@example.com")
                        .claim("role", "USER")
                        .claim("typ", "access")
                        .build()
        )).getTokenValue();

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_EXPIRED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(expired))));
    }

    @Test
    void malformedLoginRequestsRemainValidationFailures() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"person@example.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"invalid-email","password":"secret"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"person@example.com","password":"secret","role":"ADMIN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void authenticationFailuresDoNotLeakSensitiveValuesToResponsesOrLogs(CapturedOutput output) throws Exception {
        signup();
        String password = "sensitive-wrong-password";
        String invalidToken = "sensitive.invalid.token";

        MvcResult loginFailure = login("person@example.com", password);
        MvcResult tokenFailure = mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + invalidToken))
                .andReturn();

        assertThat(loginFailure.getResponse().getContentAsString()).doesNotContain(password);
        assertThat(tokenFailure.getResponse().getContentAsString()).doesNotContain(invalidToken);
        assertThat(output.getAll())
                .doesNotContain(password)
                .doesNotContain(invalidToken)
                .doesNotContain("Authorization: Bearer")
                .doesNotContain("passwordHash")
                .doesNotContain("JWT_SECRET_BASE64")
                .doesNotContain("at com.commitgotchi.security.JwtAuthenticationFilter");
    }

    private void signup() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"person@example.com","password":"very-secure-password"}
                                """))
                .andExpect(status().isCreated());
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andReturn();
    }

    private String accessToken() throws Exception {
        MvcResult result = login("person@example.com", "very-secure-password");
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }
}
