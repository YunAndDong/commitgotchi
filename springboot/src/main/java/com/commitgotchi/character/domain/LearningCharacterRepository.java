package com.commitgotchi.character.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LearningCharacterRepository extends JpaRepository<LearningCharacter, Long> {

    @Query("""
            SELECT character
            FROM LearningCharacter character
            WHERE character.user.id = :userId
            ORDER BY character.createdAt DESC, character.id DESC
            """)
    List<LearningCharacter> findAllByUserIdOrderByCreatedAtDesc(@Param("userId") long userId);

    long countByUserId(long userId);

    Optional<LearningCharacter> findByIdAndUserId(Long id, long userId);

    @Query("""
            SELECT character
            FROM LearningCharacter character
            WHERE character.user.id = :userId
              AND character.active = true
            """)
    Optional<LearningCharacter> findActiveByUserId(@Param("userId") long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT character
            FROM LearningCharacter character
            WHERE character.id = :characterId
              AND character.user.id = :userId
            """)
    Optional<LearningCharacter> findByIdAndUserIdForUpdate(
            @Param("characterId") Long characterId,
            @Param("userId") long userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT character
            FROM LearningCharacter character
            WHERE character.user.id = :userId
            ORDER BY character.createdAt DESC, character.id DESC
            """)
    List<LearningCharacter> findAllByUserIdForUpdateOrderByCreatedAtDesc(@Param("userId") long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT character
            FROM LearningCharacter character
            WHERE character.user.id = :userId
              AND character.active = true
            """)
    Optional<LearningCharacter> findActiveByUserIdForUpdate(@Param("userId") long userId);
}
