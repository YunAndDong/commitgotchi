package com.commitgotchi.auth;

import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.User;
import com.commitgotchi.user.domain.UserRepository;
import com.commitgotchi.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUsers() {
        userRepository.deleteAll();
    }

    @Test
    void signupReturnsCreatedWithoutSensitiveFieldsAndStoresBcryptUser() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"  Person@Example.COM ","password":"very-secure-password"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("person@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User user = userRepository.findAll().get(0);
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        assertThat(user.getPasswordHash()).containsPattern("^\\$2[ayb]\\$12\\$");
        assertThat(passwordEncoder.matches("very-secure-password", user.getPasswordHash())).isTrue();
    }

    @Test
    void normalizedDuplicateReturnsCommonConflictResponse() throws Exception {
        signup("person@example.com", "very-secure-password");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"  PERSON@EXAMPLE.COM  ","password":"another-secure-password"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("USER_EMAIL_CONFLICT"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void invalidAndUnknownFieldsReturnValidationFailedWithoutLeakingInput() throws Exception {
        String roleInjection = """
                {"email":"person@example.com","password":"very-secure-password","role":"ADMIN"}
                """;

        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content(roleInjection))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("very-secure-password"))));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"invalid","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"person@example.com","password":"very-secure-password","nickname":"ignored?"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    void rejectsStructurallyInvalidEmailAndAcceptsUnicodePasswordByCodePointPolicy() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"person..alias@example.com","password":"very-secure-password"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        String unicodePassword = "a".repeat(63) + "😀";
        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"unicode-password@example.com","password":"%s"}
                                """.formatted(unicodePassword)))
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentDuplicateSignupReturnsOneCreatedAndOneConflict() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> concurrentSignup("race@example.com"));
            Future<MvcResult> second = executor.submit(() -> concurrentSignup("race@example.com"));

            List<MvcResult> results = List.of(first.get(), second.get());
            assertThat(results)
                    .extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(results.stream()
                    .filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst()
                    .orElseThrow()
                    .getResponse()
                    .getContentAsString())
                    .contains("\"code\":\"USER_EMAIL_CONFLICT\"");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unsupportedMethodAndMediaTypeKeepTheirHttpSemantics() throws Exception {
        mockMvc.perform(get("/api/auth/signup"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType("text/plain")
                        .content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void testProfileExposesSignupDocsAndHealthRemainsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/auth/signup")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("VALIDATION_FAILED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("USER_EMAIL_CONFLICT")));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("springboot"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.db").value("up"));
    }

    private void signup(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated());
    }

    private MvcResult concurrentSignup(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"very-secure-password"}
                                """.formatted(email)))
                .andReturn();
    }
}
