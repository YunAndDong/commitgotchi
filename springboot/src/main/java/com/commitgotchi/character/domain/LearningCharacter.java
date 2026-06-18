package com.commitgotchi.character.domain;

import com.commitgotchi.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "characters")
public class LearningCharacter {

    private static final int EVOLUTION_BATTLE_POWER_THRESHOLD = 1_000;
    private static final String DEFAULT_STATUS_MESSAGE = "Ready to learn";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(name = "design_keyword", nullable = false, length = 120)
    private String designKeyword;

    @Column(nullable = false, length = 500)
    private String personality;

    @Column(name = "stat_db", nullable = false)
    private int statDb;

    @Column(name = "stat_algorithm", nullable = false)
    private int statAlgorithm;

    @Column(name = "stat_cs", nullable = false)
    private int statCs;

    @Column(name = "stat_network", nullable = false)
    private int statNetwork;

    @Column(name = "stat_framework", nullable = false)
    private int statFramework;

    @Column(name = "battle_power", nullable = false)
    private int battlePower;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CharacterEmotion emotion;

    @Column(name = "status_message", nullable = false, length = 160)
    private String statusMessage;

    @Column(name = "is_evolved", nullable = false)
    private boolean evolved;

    @Enumerated(EnumType.STRING)
    @Column(name = "image_status", nullable = false, length = 20)
    private CharacterImageStatus imageStatus;

    @Column(name = "sprite_sheet_url", columnDefinition = "TEXT")
    private String spriteSheetUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sprite_meta", columnDefinition = "jsonb")
    private String spriteMeta;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
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

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
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
