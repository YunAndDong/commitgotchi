package com.commitgotchi.security;

import com.commitgotchi.user.domain.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private SecurityErrorResponseWriter errorWriter;

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenSetsPrincipalAndInvokesChainOnce() throws Exception {
        MockHttpServletRequest request = bearerRequest("token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthPrincipal principal = new AuthPrincipal(42L, "person@example.com", UserRole.USER);
        when(tokenProvider.validate("token")).thenReturn(principal);

        filter().doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void invalidTokenLeavesSecurityContextEmptyAndDoesNotInvokeChain() throws Exception {
        MockHttpServletRequest request = bearerRequest("invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenProvider.validate("invalid")).thenThrow(new InvalidAccessTokenException());

        filter().doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(errorWriter).write(response, com.commitgotchi.common.error.ErrorCode.AUTH_ACCESS_TOKEN_INVALID);
        org.mockito.Mockito.verifyNoInteractions(filterChain);
    }

    @Test
    void downstreamTokenNamedExceptionIsNotConvertedToAuthenticationFailure() throws Exception {
        MockHttpServletRequest request = bearerRequest("token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenProvider.validate("token"))
                .thenReturn(new AuthPrincipal(42L, "person@example.com", UserRole.USER));
        org.mockito.Mockito.doThrow(new InvalidAccessTokenException()).when(filterChain).doFilter(request, response);

        assertThatThrownBy(() -> filter().doFilter(request, response, filterChain))
                .isInstanceOf(InvalidAccessTokenException.class);
        org.mockito.Mockito.verifyNoInteractions(errorWriter);
    }

    @Test
    void internalAuthorizationHeaderIsLeftForInternalAuthFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/report");
        request.addHeader("Authorization", "Internal shared-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
        org.mockito.Mockito.verifyNoInteractions(errorWriter, tokenProvider);
    }

    private JwtAuthenticationFilter filter() {
        return new JwtAuthenticationFilter(tokenProvider, errorWriter);
    }

    private MockHttpServletRequest bearerRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
