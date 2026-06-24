package com.commitgotchi.common.error;

import com.commitgotchi.character.application.CharacterLimitExceededException;
import com.commitgotchi.character.application.CharacterNotFoundException;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserEmailConflictException.class)
    public ResponseEntity<ErrorResponse> handleEmailConflict() {
        return response(ErrorCode.USER_EMAIL_CONFLICT);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials() {
        return response(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken() {
        return response(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    @ExceptionHandler(ReusedRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleReusedRefreshToken() {
        return response(ErrorCode.AUTH_REFRESH_TOKEN_REUSED);
    }

    @ExceptionHandler(CharacterLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleCharacterLimitExceeded() {
        return response(ErrorCode.CHARACTER_LIMIT_EXCEEDED);
    }

    @ExceptionHandler({
            InvalidSignupException.class,
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ErrorResponse> handleValidationFailure() {
        return response(ErrorCode.VALIDATION_FAILED);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        if (DatabaseConstraint.isViolation(exception, "uq_users_email")) {
            return response(ErrorCode.USER_EMAIL_CONFLICT);
        }
        if (DatabaseConstraint.isViolation(exception, "uq_one_active_character_per_user")) {
            return response(ErrorCode.VALIDATION_FAILED);
        }
        if (DatabaseConstraint.isViolation(exception, "uq_codex_character_review_user_character")) {
            return response(ErrorCode.VALIDATION_FAILED);
        }
        return response(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed() {
        return response(ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType() {
        return response(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound() {
        return response(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(CharacterNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCharacterNotFound() {
        return response(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException() {
        return response(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ErrorResponse> response(ErrorCode errorCode) {
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.from(errorCode, traceId));
    }
}
