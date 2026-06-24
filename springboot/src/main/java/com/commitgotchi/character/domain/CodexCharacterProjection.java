package com.commitgotchi.character.domain;

public class CodexCharacterProjection {

    private Long id;
    private String personality;
    private String designKeyword;
    private CharacterImageStatus imageStatus;
    private String spriteSheetUrl;
    private String spriteMeta;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPersonality() {
        return personality;
    }

    public void setPersonality(String personality) {
        this.personality = personality;
    }

    public String getDesignKeyword() {
        return designKeyword;
    }

    public void setDesignKeyword(String designKeyword) {
        this.designKeyword = designKeyword;
    }

    public CharacterImageStatus getImageStatus() {
        return imageStatus;
    }

    public void setImageStatus(CharacterImageStatus imageStatus) {
        this.imageStatus = imageStatus;
    }

    public String getSpriteSheetUrl() {
        return spriteSheetUrl;
    }

    public void setSpriteSheetUrl(String spriteSheetUrl) {
        this.spriteSheetUrl = spriteSheetUrl;
    }

    public String getSpriteMeta() {
        return spriteMeta;
    }

    public void setSpriteMeta(String spriteMeta) {
        this.spriteMeta = spriteMeta;
    }
}
