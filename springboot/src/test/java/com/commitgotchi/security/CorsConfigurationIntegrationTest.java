package com.commitgotchi.security;

import com.commitgotchi.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "commitgotchi.cors.allowed-origins=http://localhost:5173,https://app.example.com")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsConfigurationIntegrationTest extends PostgresIntegrationTest {

    private static final String LOCAL_WEB_ORIGIN = "http://localhost:5173";
    private static final String PRODUCTION_WEB_ORIGIN = "https://app.example.com";
    private static final String CHROME_EXTENSION_ORIGIN =
            "chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn";
    private static final String ALLOWED_METHODS = "GET,POST,PATCH,DELETE,OPTIONS";
    private static final String ALLOWED_HEADERS = "Authorization, Content-Type";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowedPreflightRunsBeforeAuthenticationWithApiMethodsAndHeaders() throws Exception {
        mockMvc.perform(options("/api/users/me")
                        .header("Origin", LOCAL_WEB_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", ALLOWED_HEADERS))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", LOCAL_WEB_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", ALLOWED_METHODS))
                .andExpect(header().string("Access-Control-Allow-Headers", ALLOWED_HEADERS))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void allowedActualRequestReturnsExactOriginOnly() throws Exception {
        mockMvc.perform(get("/api/health").header("Origin", PRODUCTION_WEB_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", PRODUCTION_WEB_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void trustedChromeExtensionIsAllowedWithoutAddingItToConfiguredOrigins() throws Exception {
        mockMvc.perform(options("/api/users/me")
                        .header("Origin", CHROME_EXTENSION_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", ALLOWED_HEADERS))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", CHROME_EXTENSION_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", ALLOWED_METHODS))
                .andExpect(header().string("Access-Control-Allow-Headers", ALLOWED_HEADERS))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void allowedWebAndExtensionOriginsCanPreflightVueMutatingApiMethods() throws Exception {
        for (String origin : new String[]{LOCAL_WEB_ORIGIN, PRODUCTION_WEB_ORIGIN, CHROME_EXTENSION_ORIGIN}) {
            for (String method : new String[]{"PATCH", "DELETE"}) {
                mockMvc.perform(options("/api/game/characters/1")
                                .header("Origin", origin)
                                .header("Access-Control-Request-Method", method)
                                .header("Access-Control-Request-Headers", ALLOWED_HEADERS))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Access-Control-Allow-Origin", origin))
                        .andExpect(header().string("Access-Control-Allow-Methods", ALLOWED_METHODS))
                        .andExpect(header().string("Access-Control-Allow-Headers", ALLOWED_HEADERS))
                        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
            }
        }
    }

    @Test
    void sseGetPreflightAndActualRequestKeepCorsHeadersForAllowedWebOrigin() throws Exception {
        mockMvc.perform(options("/api/game/characters/1/events")
                        .header("Origin", PRODUCTION_WEB_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", PRODUCTION_WEB_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", ALLOWED_METHODS))
                .andExpect(header().string("Access-Control-Allow-Headers", "Authorization"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        mockMvc.perform(get("/api/game/characters/1/events")
                        .header("Origin", PRODUCTION_WEB_ORIGIN)
                        .header("Authorization", "Bearer invalid-token")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", PRODUCTION_WEB_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void disallowedOriginPreflightIsRejectedWithoutCorsAllowHeader() throws Exception {
        mockMvc.perform(options("/api/users/me")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void nonAllowlistedPreflightHeaderIsRejectedWithoutCorsAllowHeader() throws Exception {
        mockMvc.perform(options("/api/users/me")
                        .header("Origin", PRODUCTION_WEB_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Requested-With"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
