package com.commitgotchi.user;

import com.commitgotchi.auth.application.RefreshTokenService;
import com.commitgotchi.auth.domain.RefreshTokenRepository;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.application.UserSoftDeleteService;
import com.commitgotchi.user.domain.User;
import com.commitgotchi.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class UserSoftDeleteServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LearningCharacterRepository characterRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserSoftDeleteService userSoftDeleteService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUsers() {
        userRepository.deleteAll();
    }

    @Test
    void softDeleteUserCascadesToOwnedCharactersAndRefreshTokens() {
        AdminTestFixture.ProvisionedUser provisioned =
                fixture.provisionUser("soft-delete-user@example.com", "very-secure-password");
        User user = userRepository.findById(provisioned.id()).orElseThrow();
        LearningCharacter active = LearningCharacter.create(user, "active", "active-design", "active-personality");
        active.activate();
        characterRepository.save(active);
        characterRepository.save(LearningCharacter.create(user, "inactive", "inactive-design", "inactive-personality"));
        refreshTokenService.issue(user);
        refreshTokenService.issue(user);

        assertThat(userSoftDeleteService.softDelete(user.getId())).isTrue();

        assertThat(userRepository.findById(user.getId())).isEmpty();
        assertThat(characterRepository.countByUserId(user.getId())).isZero();
        assertThat(refreshTokenRepository.countByUserIdAndRevokedAtIsNull(user.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM users WHERE id = ?",
                Boolean.class,
                user.getId()
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT count(*)
                        FROM characters
                        WHERE user_id = ?
                          AND deleted_at IS NOT NULL
                          AND is_active = false
                        """,
                Integer.class,
                user.getId()
        )).isEqualTo(2);

        assertThat(userSoftDeleteService.softDelete(user.getId())).isFalse();
    }
}
