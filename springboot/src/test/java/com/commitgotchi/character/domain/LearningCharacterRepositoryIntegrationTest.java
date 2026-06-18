package com.commitgotchi.character.domain;

import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.User;
import com.commitgotchi.user.domain.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LearningCharacterRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LearningCharacterRepository characterRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findsCharactersByUserAndCountsOwnedCharacters() {
        User user = saveUser();
        User otherUser = saveUser();
        LearningCharacter first = characterRepository.save(
                LearningCharacter.create(user, "first", "first-design", "first-personality")
        );
        LearningCharacter second = characterRepository.save(
                LearningCharacter.create(user, "second", "second-design", "second-personality")
        );
        characterRepository.save(LearningCharacter.create(otherUser, "other", "other-design", "other-personality"));
        characterRepository.flush();
        jdbcTemplate.update(
                "UPDATE characters SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00' WHERE id IN (?, ?)",
                first.getId(),
                second.getId()
        );
        entityManager.clear();

        assertThat(characterRepository.countByUserId(user.getId())).isEqualTo(2);
        assertThat(characterRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()))
                .extracting(LearningCharacter::getName)
                .containsExactly("second", "first");
    }

    @Test
    void findsOwnedCharacterWithPessimisticWriteLockExtensionPoint() throws NoSuchMethodException {
        User owner = saveUser();
        User otherUser = saveUser();
        LearningCharacter character = characterRepository.saveAndFlush(
                LearningCharacter.create(owner, "locked", "locked-design", "locked-personality")
        );

        assertThat(characterRepository.findByIdAndUserId(character.getId(), owner.getId())).isPresent();
        assertThat(characterRepository.findByIdAndUserId(character.getId(), otherUser.getId())).isEmpty();
        assertThat(characterRepository.findByIdAndUserIdForUpdate(character.getId(), owner.getId())).isPresent();

        assertPessimisticWriteLock(
                "findByIdAndUserIdForUpdate",
                Long.class,
                long.class
        );
        assertPessimisticWriteLock("findAllByUserIdForUpdateOrderByCreatedAtDesc", long.class);
        assertPessimisticWriteLock("findActiveByUserIdForUpdate", long.class);
    }

    private void assertPessimisticWriteLock(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method lockMethod = LearningCharacterRepository.class.getMethod(methodName, parameterTypes);
        assertThat(lockMethod.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private User saveUser() {
        return userRepository.save(User.create(
                "character-repo-" + UUID.randomUUID() + "@example.com",
                "$2a$12$hash"
        ));
    }
}
