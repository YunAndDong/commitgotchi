package com.commitgotchi.security;

import com.commitgotchi.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class InternalApiAuthenticationFilterTest {

    @Mock
    private SecurityErrorResponseWriter errorWriter;

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validInternalSecretSetsAuthorityAndInvokesChain() throws Exception {
        MockHttpServletRequest request = internalRequest("correct-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter("correct-secret").doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("internal-api");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly(InternalApiAuthenticationFilter.AUTHORITY);
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(errorWriter);
    }

    @Test
    void wrongInternalSecretIsRejectedWithoutEchoingSecret() throws Exception {
        MockHttpServletRequest request = internalRequest("wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter("correct-secret").doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(errorWriter).write(response, ErrorCode.AUTH_ACCESS_TOKEN_INVALID);
        verifyNoInteractions(filterChain);
    }

    @Test
    void missingInternalHeaderIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/report");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter("correct-secret").doFilter(request, response, filterChain);

        verify(errorWriter).write(response, ErrorCode.AUTH_ACCESS_TOKEN_MISSING);
        verifyNoInteractions(filterChain);
    }

    @Test
    void nonInternalPathIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/game/state");
        request.addHeader("Authorization", "Internal correct-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter("correct-secret").doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(errorWriter);
    }

    private InternalApiAuthenticationFilter filter(String secret) {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setSecret(secret);
        return new InternalApiAuthenticationFilter(properties, errorWriter);
    }

    private MockHttpServletRequest internalRequest(String secret) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/report");
        request.addHeader("Authorization", "Internal " + secret);
        return request;
    }
}
