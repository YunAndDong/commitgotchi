package com.commitgotchi.support;

import com.commitgotchi.security.JwtTokenProvider;
import com.commitgotchi.user.domain.User;
import com.commitgotchi.user.domain.UserRepository;
import com.commitgotchi.user.domain.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 테스트 전용 사용자/토큰 프로비저닝 헬퍼.
 *
 * <p>운영 코드 경로(공개 가입 API)로는 ADMIN을 만들 수 없으므로({@link User#create}는 항상 USER 강제),
 * 이 헬퍼는 가입 후 네이티브 SQL로 {@code role='ADMIN'}을 갱신해 테스트 ADMIN을 마련한다.
 * 실제 자격 증명을 소스에 하드코딩하지 않으며, V1 스키마와 정규화 이메일·BCrypt 제약을 위반하지 않는다.
 *
 * <p>이 빈은 {@code test} 소스셋에만 존재하므로 운영 빌드에 포함되지 않는다.
 */
@Component
public class AdminTestFixture {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminTestFixture(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider tokenProvider
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    /**
     * USER Role 사용자를 생성하고 유효한 Access Token을 발급한다.
     */
    @Transactional
    public ProvisionedUser provisionUser(String rawEmail, String rawPassword) {
        User user = persistUser(rawEmail, rawPassword);
        return toProvisioned(user, UserRole.USER);
    }

    /**
     * 가입 후 role='ADMIN'으로 갱신해 ADMIN Role 사용자를 생성하고 유효한 Access Token을 발급한다.
     */
    @Transactional
    public ProvisionedUser provisionAdmin(String rawEmail, String rawPassword) {
        User user = persistUser(rawEmail, rawPassword);
        // 운영 가입 경로는 항상 USER이므로, 테스트 전용으로만 role을 ADMIN으로 승격한다.
        entityManager.createNativeQuery("UPDATE users SET role = 'ADMIN' WHERE id = :id")
                .setParameter("id", user.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        return toProvisioned(user, UserRole.ADMIN);
    }

    private User persistUser(String rawEmail, String rawPassword) {
        String email = normalize(rawEmail);
        User user = User.create(email, passwordEncoder.encode(rawPassword));
        User saved = userRepository.save(user);
        entityManager.flush();
        return saved;
    }

    private ProvisionedUser toProvisioned(User user, UserRole role) {
        String email = normalize(user.getEmail());
        String accessToken = tokenProvider.issue(user.getId(), email, role).value();
        return new ProvisionedUser(user.getId(), email, role, accessToken);
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public record ProvisionedUser(long id, String email, UserRole role, String accessToken) {

        public String bearer() {
            return "Bearer " + accessToken;
        }
    }
}
