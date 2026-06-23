package com.commitgotchi.character.domain;

import com.commitgotchi.user.domain.User;

import java.time.Instant;
import java.util.Objects;

public class LearningCharacter {

    private static final int EVOLUTION_BATTLE_POWER_THRESHOLD = 1_000;
    private static final String DEFAULT_STATUS_MESSAGE = "Ready to learn";

    private Long id;

    private User user;

    private String name;

    private String designKeyword;

    private String personality;

    private int statDb;

    private int statAlgorithm;

    private int statCs;

    private int statNetwork;

    private int statFramework;

    private int battlePower;

    private CharacterEmotion emotion;

    private String statusMessage;

    private boolean evolved;

    private CharacterImageStatus imageStatus;

    private String spriteSheetUrl;

    private String spriteMeta;

    private boolean active;

    private long version;

    private Instant createdAt;

    private Instant updatedAt;

    protected LearningCharacter() {
    }

    private LearningCharacter(User user, String name, String designKeyword, String personality) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.name = requireText(name, "name", 40);
        this.designKeyword = requireText(designKeyword, "designKeyword", 120);
        this.personality = requireText(personality, "personality", 500);
        this.statusMessage = DEFAULT_STATUS_MESSAGE;
        this.emotion = CharacterEmotion.JOY;
        this.imageStatus = CharacterImageStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static LearningCharacter create(User user, String name, String designKeyword, String personality) {
        return new LearningCharacter(user, name, designKeyword, personality);
    }

    public void rename(String name) {
        this.name = requireText(name, "name", 40);
        touch();
    }

    public void changeDesignKeyword(String designKeyword) {
        this.designKeyword = requireText(designKeyword, "designKeyword", 120);
        touch();
    }

    public void changePersonality(String personality) {
        this.personality = requireText(personality, "personality", 500);
        touch();
    }

    public void applyScoreDelta(int dbDelta, int algorithmDelta, int csDelta, int networkDelta, int frameworkDelta) {
        int nextDb = addDelta(statDb, dbDelta);
        int nextAlgorithm = addDelta(statAlgorithm, algorithmDelta);
        int nextCs = addDelta(statCs, csDelta);
        int nextNetwork = addDelta(statNetwork, networkDelta);
        int nextFramework = addDelta(statFramework, frameworkDelta);
        int nextBattlePower = calculateBattlePower(nextDb, nextAlgorithm, nextCs, nextNetwork, nextFramework);

        this.statDb = nextDb;
        this.statAlgorithm = nextAlgorithm;
        this.statCs = nextCs;
        this.statNetwork = nextNetwork;
        this.statFramework = nextFramework;
        this.battlePower = nextBattlePower;
        evolveIfEligible();
        touch();
    }

    public void markReady(String spriteSheetUrl, String spriteMeta) {
        this.spriteSheetUrl = requireText(spriteSheetUrl, "spriteSheetUrl");
        this.spriteMeta = spriteMeta;
        this.imageStatus = CharacterImageStatus.READY;
        touch();
    }

    public void markFallback(String spriteSheetUrl, String spriteMeta) {
        this.spriteSheetUrl = requireText(spriteSheetUrl, "spriteSheetUrl");
        this.spriteMeta = spriteMeta;
        this.imageStatus = CharacterImageStatus.FALLBACK;
        touch();
    }

    public void markFailed() {
        this.spriteSheetUrl = null;
        this.spriteMeta = null;
        this.imageStatus = CharacterImageStatus.FAILED;
        touch();
    }

    public void markPending() {
        this.spriteSheetUrl = null;
        this.spriteMeta = null;
        this.imageStatus = CharacterImageStatus.PENDING;
        touch();
    }

    public void react(CharacterEmotion emotion, String statusMessage) {
        this.emotion = Objects.requireNonNull(emotion, "emotion must not be null");
        this.statusMessage = requireText(statusMessage, "statusMessage", 160);
        touch();
    }

    public void activate() {
        this.active = true;
        touch();
    }

    public void deactivate() {
        this.active = false;
        touch();
    }

    private int addDelta(int currentValue, int delta) {
        int nextValue = Math.addExact(currentValue, delta);
        if (nextValue < 0) {
            throw new IllegalArgumentException("stat cannot be negative");
        }
        return nextValue;
    }

    private int calculateBattlePower(int db, int algorithm, int cs, int network, int framework) {
        long total = (long) db + algorithm + cs + network + framework;
        return Math.toIntExact(total);
    }

    private void evolveIfEligible() {
        if (!evolved && battlePower >= EVOLUTION_BATTLE_POWER_THRESHOLD) {
            this.evolved = true;
        }
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String requireText(String value, String fieldName, int maxLength) {
        String text = requireText(value, fieldName);
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maxLength + " characters");
        }
        return text;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getName() {
        return name;
    }

    public String getDesignKeyword() {
        return designKeyword;
    }

    public String getPersonality() {
        return personality;
    }

    public int getStatDb() {
        return statDb;
    }

    public int getStatAlgorithm() {
        return statAlgorithm;
    }

    public int getStatCs() {
        return statCs;
    }

    public int getStatNetwork() {
        return statNetwork;
    }

    public int getStatFramework() {
        return statFramework;
    }

    public int getBattlePower() {
        return battlePower;
    }

    public CharacterEmotion getEmotion() {
        return emotion;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public boolean isEvolved() {
        return evolved;
    }

    public CharacterImageStatus getImageStatus() {
        return imageStatus;
    }

    public String getSpriteSheetUrl() {
        return spriteSheetUrl;
    }

    public String getSpriteMeta() {
        return spriteMeta;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
