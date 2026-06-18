package com.commitgotchi.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    USER_EMAIL_CONFLICT(HttpStatus.CONFLICT, "An account with this email already exists."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "The email or password is incorrect."),
    AUTH_ACCESS_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "An access token is required."),
    AUTH_ACCESS_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "The access token is invalid."),
    AUTH_ACCESS_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "The access token has expired."),
    AUTH_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "The refresh token is invalid."),
    AUTH_REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "The refresh token has already been used."),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to access this resource."),
    CHARACTER_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "You can create up to 3 characters."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request is invalid."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not supported."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The media type is not supported."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
