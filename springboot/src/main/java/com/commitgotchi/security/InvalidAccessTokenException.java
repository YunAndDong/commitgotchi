package com.commitgotchi.security;

public class InvalidAccessTokenException extends RuntimeException {

    public InvalidAccessTokenException() {
    }

    public InvalidAccessTokenException(Throwable cause) {
        super(cause);
    }
}
