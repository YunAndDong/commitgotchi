package com.commitgotchi.auth.application;

import com.commitgotchi.common.error.InvalidSignupException;
import com.commitgotchi.common.error.InvalidCredentialsException;
import com.commitgotchi.security.AuthUserDetails;
import com.commitgotchi.security.IssuedAccessToken;
import com.commitgotchi.security.JwtTokenProvider;
import com.commitgotchi.common.error.UserEmailConflictException;
import com.commitgotchi.user.domain.User;
import com.commitgotchi.user.domain.UserRepository;
import com.commitgotchi.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.hibernate.exception.ConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Test
    void loginNormalizesEmailAndIssuesAccessTokenFromAuthenticatedUser() {
        AuthUserDetails user = new AuthUserDetails(42L, "person@example.com", "$2a$12$hash", UserRole.USER);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        when(jwtTokenProvider.issue(42L, "person@example.com", UserRole.USER))
                .thenReturn(new IssuedAccessToken("token", java.time.Instant.parse("2026-06-12T10:15:00Z")));
        User domainUser = User.create("person@example.com", "$2a$12$hash");
        when(userRepository.findById(42L)).thenReturn(java.util.Optional.of(domainUser));
        when(refreshTokenService.issue(domainUser))
                .thenReturn(new IssuedRefreshToken("refresh", java.time.Instant.parse("2026-07-12T10:00:00Z")));

        AuthService service = new AuthService(
                userRepository,
                passwordEncoder,
                authenticationManager,
                jwtTokenProvider,
                refreshTokenService
        );

        assertThat(service.login("  PERSON@EXAMPLE.COM ", "password").accessToken()).isEqualTo("token");
        ArgumentCaptor<UsernamePasswordAuthenticationToken> captor =
                ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());
        assertThat(captor.getValue().getPrincipal()).isEqualTo("person@example.com");
    }

    @Test
    void loginMapsAuthenticationFailureToSafeCredentialsError() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("internal detail"));
        AuthService service = new AuthService(
                userRepository,
                passwordEncoder,
                authenticationManager,
                jwtTokenProvider,
                refreshTokenService
        );

        assertThatThrownBy(() -> service.login("person@example.com", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(null);
    }

    @Test
    void loginRejectsPasswordsLongerThanSeventyTwoUtf8BytesBeforeBcryptTruncation() {
        AuthService service = new AuthService(
                userRepository,
                passwordEncoder,
                authenticationManager,
                jwtTokenProvider,
                refreshTokenService
        );

        assertThatThrownBy(() -> service.login("person@example.com", "a".repeat(72) + "suffix"))
                .isInstanceOf(InvalidCredentialsException.class);
        org.mockito.Mockito.verifyNoInteractions(authenticationManager);
    }

    @Test
    void loginDoesNotHideAuthenticationInfrastructureFailuresAsBadCredentials() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new AuthenticationServiceException("database unavailable"));
        AuthService service = new AuthService(
                userRepository,
                passwordEncoder,
                authenticationManager,
                jwtTokenProvider,
                refreshTokenService
        );

        assertThatThrownBy(() -> service.login("person@example.com", "password"))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void normalizesEmailHashesPasswordAndForcesUserRole() {
        when(userRepository.existsByEmail("person@example.com")).thenReturn(false);
        when(passwordEncoder.encode("very-secure-password")).thenReturn("$2a$12$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthService service = new AuthService(userRepository, passwordEncoder);
        service.signup("  Person@Example.COM ", "very-secure-password");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("person@example.com");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$12$hash");
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void rejectsPasswordLongerThanSeventyTwoUtf8Bytes() {
        AuthService service = new AuthService(userRepository, passwordEncoder);
        String password = "가".repeat(25);

        assertThatThrownBy(() -> service.signup("person@example.com", password))
                .isInstanceOf(InvalidSignupException.class);
    }

    @Test
    void acceptsPasswordAtExactlySeventyTwoUtf8Bytes() {
        String password = "가".repeat(24);
        when(userRepository.existsByEmail("person@example.com")).thenReturn(false);
        when(passwordEncoder.encode(password)).thenReturn("$2a$12$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthService service = new AuthService(userRepository, passwordEncoder);

        assertThat(service.signup("person@example.com", password).role()).isEqualTo(UserRole.USER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456789012", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void acceptsTwelveAndSixtyFourCharacterBoundaries(String password) {
        when(userRepository.existsByEmail("person@example.com")).thenReturn(false);
        when(passwordEncoder.encode(password)).thenReturn("$2a$12$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthService service = new AuthService(userRepository, passwordEncoder);

        assertThat(service.signup("person@example.com", password).role()).isEqualTo(UserRole.USER);
    }

    @Test
    void mapsOnlyEmailUniqueConstraintToConflict() {
        when(userRepository.existsByEmail("person@example.com")).thenReturn(false);
        when(passwordEncoder.encode("very-secure-password")).thenReturn("$2a$12$hash");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "localized database error",
                        constraintViolation("uq_users_email")
                ));

        AuthService service = new AuthService(userRepository, passwordEncoder);

        assertThatThrownBy(() -> service.signup("person@example.com", "very-secure-password"))
                .isInstanceOf(UserEmailConflictException.class);
    }

    @Test
    void rejectsEmailThatExceedsLimitAfterLowercaseNormalization() {
        AuthService service = new AuthService(userRepository, passwordEncoder);
        String email = "İ" + "a".repeat(248) + "@x.co";

        assertThat(email).hasSize(254);
        assertThatThrownBy(() -> service.signup(email, "very-secure-password"))
                .isInstanceOf(InvalidSignupException.class);
    }

    private ConstraintViolationException constraintViolation(String constraintName) {
        return new ConstraintViolationException(
                "localized database error",
                new java.sql.SQLException("localized database error"),
                constraintName
        );
    }
}
