package com.commitgotchi.character.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = false)
public record FastApiScoreDelta(
        @NotNull @Min(0) @Max(10) Integer db,
        @NotNull @Min(0) @Max(10) Integer algorithm,
        @NotNull @Min(0) @Max(10) Integer cs,
        @NotNull @Min(0) @Max(10) Integer network,
        @NotNull @Min(0) @Max(10) Integer framework
) {

    public int dbDelta() {
        return db;
    }

    public int algorithmDelta() {
        return algorithm;
    }

    public int csDelta() {
        return cs;
    }

    public int networkDelta() {
        return network;
    }

    public int frameworkDelta() {
        return framework;
    }

    public int sum() {
        return db + algorithm + cs + network + framework;
    }
}
