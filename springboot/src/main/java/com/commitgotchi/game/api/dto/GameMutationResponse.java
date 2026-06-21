package com.commitgotchi.game.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record GameMutationResponse(JsonNode state, JsonNode item) {
}
