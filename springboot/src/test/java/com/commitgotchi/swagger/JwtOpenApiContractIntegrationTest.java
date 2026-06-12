package com.commitgotchi.swagger;

import com.commitgotchi.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtOpenApiContractIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void documentsBearerLoginAndProtectedCurrentUserContracts() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/TokenPairResponse"))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['400'].description")
                        .value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['401'].description")
                        .value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post").exists())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/TokenPairResponse"))
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.responses['401'].description")
                        .value("AUTH_REFRESH_TOKEN_INVALID / AUTH_REFRESH_TOKEN_REUSED"))
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post").exists())
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.responses['204'].content").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.description")
                        .value(org.hamcrest.Matchers.containsString("최대 15분")))
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.requestBody.content['application/json']"
                        + ".schema['$ref']").value("#/components/schemas/RefreshTokenRequest"))
                .andExpect(jsonPath("$.paths['/api/users/me'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/users/me'].get.responses['401'].description")
                        .value("AUTH_ACCESS_TOKEN_MISSING / AUTH_ACCESS_TOKEN_INVALID / AUTH_ACCESS_TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.components.schemas.TokenPairResponse.properties.refreshToken.example")
                        .value("<refresh-token>"))
                .andExpect(jsonPath("$.components.schemas.RefreshTokenRequest.properties.refreshToken.example")
                        .value("<refresh-token>"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("JWT_SECRET_BASE64"))));
    }
}
