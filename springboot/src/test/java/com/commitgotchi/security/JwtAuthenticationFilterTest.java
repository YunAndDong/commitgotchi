package com.commitgotchi.security;

import com.commitgotchi.user.domain.User;
import com.commitgotchi.user.domain.UserRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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

    @Mock
    private UserRepository userRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenSetsPrincipalAndInvokesChainOnce() throws Exception {
        MockHttpServletRequest request = bearerRequest("token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthPrincipal principal = new AuthPrincipal(42L, "person@example.com", UserRole.USER);
        User user = aliveUser(42L, "person@example.com", UserRole.USER);
        when(tokenProvider.validate("token")).thenReturn(principal);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

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
        User user = aliveUser(42L, "person@example.com", UserRole.USER);
        when(tokenProvider.validate("token"))
                .thenReturn(new AuthPrincipal(42L, "person@example.com", UserRole.USER));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
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

    @Test
    void validTokenForDeletedUserIsRejectedBeforeSecurityContextIsSet() throws Exception {
        MockHttpServletRequest request = bearerRequest("token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenProvider.validate("token"))
                .thenReturn(new AuthPrincipal(42L, "person@example.com", UserRole.USER));
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        filter().doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(errorWriter).write(response, com.commitgotchi.common.error.ErrorCode.AUTH_ACCESS_TOKEN_INVALID);
        org.mockito.Mockito.verifyNoInteractions(filterChain);
    }

    private JwtAuthenticationFilter filter() {
        return new JwtAuthenticationFilter(tokenProvider, errorWriter, userRepository);
    }

    private User aliveUser(long id, String email, UserRole role) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getEmail()).thenReturn(email);
        when(user.getRole()).thenReturn(role);
        return user;
    }

    private MockHttpServletRequest bearerRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
