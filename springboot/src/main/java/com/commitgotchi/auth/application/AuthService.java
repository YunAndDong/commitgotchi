package com.commitgotchi.auth.application;

import com.commitgotchi.auth.api.dto.TokenPairResponse;
import com.commitgotchi.auth.api.dto.SignupResponse;
import com.commitgotchi.common.error.DatabaseConstraint;
import com.commitgotchi.common.error.InvalidCredentialsException;
import com.commitgotchi.common.error.InvalidSignupException;
import com.commitgotchi.common.error.UserEmailConflictException;
import com.commitgotchi.security.AuthUserDetails;
import com.commitgotchi.security.JwtTokenProvider;
import com.commitgotchi.user.domain.User;
import com.commitgotchi.user.domain.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    /**
     * signup 전용 테스트 시드 생성자. login()은 인증/토큰 협력자가 없어 호출하면 안 된다.
     */
    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this(userRepository, passwordEncoder, null, null, null);
    }

    @Autowired
    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public TokenPairResponse login(String email, String password) {
        if (email == null
                || password == null
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidCredentialsException();
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.length() > 254) {
            throw new InvalidCredentialsException();
        }
        try {
            AuthUserDetails user = (AuthUserDetails) authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedEmail, password)
            ).getPrincipal();
            User domainUser = userRepository.findById(user.userId())
                    .orElseThrow(InvalidCredentialsException::new);
            return TokenPairResponse.from(
                    jwtTokenProvider.issue(user.userId(), user.email(), user.role()),
                    refreshTokenService.issue(domainUser)
            );
        } catch (BadCredentialsException exception) {
            throw new InvalidCredentialsException();
        }
    }

    @Transactional
    public SignupResponse signup(String email, String password) {
        validatePassword(password);
        if (email == null) {
            throw new InvalidSignupException();
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.length() > 254) {
            throw new InvalidSignupException();
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new UserEmailConflictException();
        }

        User user = User.create(normalizedEmail, passwordEncoder.encode(password));
        try {
            return SignupResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseConstraint.isViolation(exception, "uq_users_email")) {
                throw new UserEmailConflictException();
            }
            throw exception;
        }
    }

    private void validatePassword(String password) {
        int characterCount = password == null ? 0 : password.codePointCount(0, password.length());
        if (password == null
                || characterCount < 12
                || characterCount > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidSignupException();
        }
    }
}
