package com.commitgotchi.character.application;

public class CharacterLimitExceededException extends RuntimeException {

    public CharacterLimitExceededException() {
        super("Character limit exceeded.");
    }
}
