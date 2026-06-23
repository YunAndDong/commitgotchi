package com.commitgotchi.game.domain;

import java.time.Instant;

public class GameState {

    private Long userId;

    private String stateJson;

    private Instant createdAt;

    private Instant updatedAt;

    protected GameState() {
    }

    private GameState(Long userId, String stateJson) {
        this.userId = userId;
        this.stateJson = stateJson;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static GameState create(Long userId, String stateJson) {
        return new GameState(userId, stateJson);
    }

    public Long getUserId() {
        return userId;
    }

    public String getStateJson() {
        return stateJson;
    }

    public void updateStateJson(String stateJson) {
        this.stateJson = stateJson;
        this.updatedAt = Instant.now();
    }
}
