package com.commitgotchi.game.domain;

import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class GameStateRepository {

    private final GameStateMapper mapper;

    public GameStateRepository(GameStateMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<GameState> findById(Long userId) {
        return Optional.ofNullable(mapper.findById(userId));
    }

    public Optional<GameState> findByIdForUpdate(Long userId) {
        return Optional.ofNullable(mapper.findByIdForUpdate(userId));
    }

    public GameState save(GameState state) {
        mapper.upsert(state);
        return state;
    }

    public GameState saveAndFlush(GameState state) {
        return save(state);
    }
}
