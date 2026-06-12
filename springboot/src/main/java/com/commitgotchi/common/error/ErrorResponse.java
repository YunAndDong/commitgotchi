package com.commitgotchi.common.error;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "공통 오류 응답")
public record ErrorResponse(
        @Schema(example = "400") int status,
        @Schema(example = "VALIDATION_FAILED") String code,
        @Schema(example = "The request is invalid.") String message,
        Instant timestamp,
        @Schema(example = "6d21d672cc8f4ac6a8dd07f2c4977b9f") String traceId
) {
    public static ErrorResponse from(ErrorCode errorCode, String traceId) {
        return new ErrorResponse(
                errorCode.getStatus().value(),
                errorCode.name(),
                errorCode.getMessage(),
                Instant.now(),
                traceId
        );
    }
}
