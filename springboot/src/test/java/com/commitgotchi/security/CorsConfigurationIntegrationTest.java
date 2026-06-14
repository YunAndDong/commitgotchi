package com.commitgotchi.security;

import com.commitgotchi.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

    private static final String CHROME_EXTENSION_ORIGIN =
            "chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowedPreflightRunsBeforeAuthenticationWithMinimalMethodsAndHeaders() throws Exception {
        mockMvc.perform(options("/api/users/me")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,OPTIONS"))
                .andExpect(header().string("Access-Control-Allow-Headers", "Authorization"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void allowedActualRequestReturnsExactOriginOnly() throws Exception {
        mockMvc.perform(get("/api/health").header("Origin", "https://app.example.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void trustedChromeExtensionIsAllowedWithoutAddingItToConfiguredOrigins() throws Exception {
        mockMvc.perform(options("/api/users/me")
                        .header("Origin", CHROME_EXTENSION_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", CHROME_EXTENSION_ORIGIN))
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
}
