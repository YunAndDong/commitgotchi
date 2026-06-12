package com.commitgotchi.admin;

import com.commitgotchi.security.JwtConfiguration;
import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.AdminTestFixture.ProvisionedUser;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AdminAuthorizationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminTestFixture adminTestFixture;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtConfiguration jwtConfiguration;

    @BeforeEach
    void cleanUsers() {
        userRepository.deleteAll();
    }

    @Test
    void userAndAdminBothAccessProtectedCurrentUserApi() throws Exception {
        ProvisionedUser user = adminTestFixture.provisionUser("user@example.com", "very-secure-password");
        ProvisionedUser admin = adminTestFixture.provisionAdmin("admin@example.com", "very-secure-password");

        mockMvc.perform(get("/api/users/me").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));

        mockMvc.perform(get("/api/users/me").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void adminAccessesAdminApiSuccessfully() throws Exception {
        ProvisionedUser admin = adminTestFixture.provisionAdmin("admin@example.com", "very-secure-password");

        mockMvc.perform(get("/api/admin/ping").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void userIsForbiddenFromAdminApiWithSafeErrorContract(CapturedOutput output) throws Exception {
        ProvisionedUser user = adminTestFixture.provisionUser("user@example.com", "very-secure-password");

        MvcResult result = mockMvc.perform(get("/api/admin/ping").header("Authorization", user.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString(user.accessToken()))))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(user.accessToken());
        assertThat(body).doesNotContain("Authorization");
        assertThat(body).doesNotContain("AccessDeniedException");
        assertThat(output.getAll())
                .doesNotContain(user.accessToken())
                .doesNotContain("Authorization: Bearer")
                .doesNotContain("at com.commitgotchi.security.RestAccessDeniedHandler");
    }

    @Test
    void missingTokenOnAdminApiReturns401NotForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_MISSING"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void tamperedAndExpiredTokensOnAdminApiReturn401DistinctFrom403() throws Exception {
        ProvisionedUser admin = adminTestFixture.provisionAdmin("admin@example.com", "very-secure-password");
        String accessToken = admin.accessToken();
        int signatureStart = accessToken.lastIndexOf('.') + 1;
        char first = accessToken.charAt(signatureStart);
        char replacement = first == 'A' ? 'B' : 'A';
        String tampered = accessToken.substring(0, signatureStart)
                + replacement
                + accessToken.substring(signatureStart + 1);

        mockMvc.perform(get("/api/admin/ping").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_INVALID"));

        // 만료 토큰: issuer/TTL/subject/email을 설정·프로비저닝 값에서 파생해 매직넘버 강결합을 제거한다.
        // expiresAt - issuedAt = accessTokenTtl 이어야 validate()의 Duration 검사를 통과해 EXPIRED 분기에 도달한다.
        Duration ttl = jwtConfiguration.accessTokenTtl();
        Instant expiresAt = Instant.now().minusSeconds(1);
        Instant issuedAt = expiresAt.minus(ttl);
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                JwtClaimsSet.builder()
                        .issuer(jwtConfiguration.issuer())
                        .subject(Long.toString(admin.id()))
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .claim("email", admin.email())
                        .claim("role", admin.role().name())
                        .claim("typ", "access")
                        .build()
        )).getTokenValue();

        mockMvc.perform(get("/api/admin/ping").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_EXPIRED"));
    }

    @Test
    void openApiDocumentsAdminPingBearerAndResponsesSafely() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/admin/ping'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/admin/ping'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/ping'].get.responses['403'].description")
                        .value("AUTH_FORBIDDEN"))
                .andExpect(content().string(not(containsString("JWT_SECRET_BASE64"))));
    }
}
