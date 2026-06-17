package com.commitgotchi.game.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "game_states")
public class GameState {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "state_json", nullable = false, columnDefinition = "TEXT")
    private String stateJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
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
