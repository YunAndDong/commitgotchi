package com.commitgotchi.game.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GameStateRepository extends JpaRepository<GameState, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT state
            FROM GameState state
            WHERE state.userId = :userId
            """)
    Optional<GameState> findByIdForUpdate(@Param("userId") Long userId);
}
