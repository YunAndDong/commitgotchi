package com.commitgotchi.game.api;

import com.commitgotchi.character.api.dto.CharacterCreateRequest;
import com.commitgotchi.character.api.dto.CharacterUpdateRequest;
import com.commitgotchi.game.api.dto.GameMutationResponse;
import com.commitgotchi.game.api.dto.GameStateResponse;
import com.commitgotchi.game.application.GameService;
import com.commitgotchi.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/game")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Game")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @Operation(summary = "게임 상태 조회", description = "정규화 characters row를 응답 시점에 state.characters로 projection합니다.")
    @GetMapping("/state")
    public GameStateResponse state(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal) {
        return new GameStateResponse(gameService.state(principal.userId()));
    }

    @Operation(summary = "캐릭터 생성", description = "사용자당 최대 3개 캐릭터를 허용하고 새 캐릭터를 active로 지정합니다.")
    @PostMapping("/characters")
    public GameMutationResponse createCharacter(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CharacterCreateRequest request
    ) {
        return gameService.createCharacter(principal.userId(), request);
    }

    @Operation(summary = "캐릭터 상세 조회", description = "자기 캐릭터만 조회합니다. malformed, missing, cross-owner id는 NOT_FOUND로 숨깁니다.")
    @GetMapping("/characters/{id}")
    public GameMutationResponse getCharacter(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "Character id", example = "1") @PathVariable String id
    ) {
        return gameService.getCharacter(principal.userId(), id);
    }

    @Operation(summary = "캐릭터 수정", description = "사용자가 편집 가능한 name, keyword, personality만 수정합니다.")
    @PatchMapping("/characters/{id}")
    public GameMutationResponse updateCharacter(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "Character id", example = "1") @PathVariable String id,
            @Valid @RequestBody CharacterUpdateRequest request
    ) {
        return gameService.updateCharacter(principal.userId(), id, request);
    }

    @Operation(summary = "활성 캐릭터 지정", description = "기존 active 캐릭터를 해제하고 지정한 자기 캐릭터를 active로 만듭니다.")
    @PatchMapping("/characters/{id}/active")
    public GameMutationResponse setActiveCharacter(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "Character id", example = "1") @PathVariable String id
    ) {
        return gameService.setActiveCharacter(principal.userId(), id);
    }

    @Operation(summary = "캐릭터 이미지 재시도", description = "READY는 no-op이고 PENDING, FAILED, FALLBACK은 이미지 생성을 다시 시도합니다.")
    @PostMapping("/characters/{id}/retry-image")
    public GameMutationResponse retryImage(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "Character id", example = "1") @PathVariable String id
    ) {
        return gameService.retryImage(principal.userId(), id);
    }

    @Operation(summary = "캐릭터 삭제", description = "자기 캐릭터만 삭제합니다. active 삭제 시 최신 남은 캐릭터가 active가 됩니다.")
    @DeleteMapping("/characters/{id}")
    public GameMutationResponse deleteCharacter(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "Character id", example = "1") @PathVariable String id
    ) {
        return gameService.deleteCharacter(principal.userId(), id);
    }

    @PostMapping("/reports")
    public GameMutationResponse saveReport(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody JsonNode request
    ) {
        return gameService.saveReport(principal.userId(), request);
    }

    @PostMapping("/quizzes/{id}/submit")
    public GameMutationResponse submitQuiz(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @RequestBody JsonNode request
    ) {
        return gameService.submitQuiz(principal.userId(), id, request);
    }

    @PostMapping("/daily-report/deliver")
    public GameMutationResponse deliverDailyReport(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody(required = false) JsonNode request
    ) {
        return gameService.deliverDailyReport(principal.userId(), request);
    }

    @PostMapping("/board-posts")
    public GameMutationResponse createBoardPost(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody JsonNode request
    ) {
        return gameService.createBoardPost(principal.userId(), request);
    }

    @PatchMapping("/board-posts/{id}")
    public GameMutationResponse updateBoardPost(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @RequestBody JsonNode request
    ) {
        return gameService.updateBoardPost(principal.userId(), id, request);
    }

    @DeleteMapping("/board-posts/{id}")
    public GameMutationResponse deleteBoardPost(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id
    ) {
        return gameService.deleteBoardPost(principal.userId(), id);
    }

    @PostMapping("/board-posts/{postId}/reviews")
    public GameMutationResponse addReview(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String postId,
            @RequestBody JsonNode request
    ) {
        return gameService.addReview(principal.userId(), postId, request);
    }

    @PatchMapping("/board-posts/{postId}/reviews/{reviewId}")
    public GameMutationResponse updateReview(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String postId,
            @PathVariable String reviewId,
            @RequestBody JsonNode request
    ) {
        return gameService.updateReview(principal.userId(), postId, reviewId, request);
    }

    @DeleteMapping("/board-posts/{postId}/reviews/{reviewId}")
    public GameMutationResponse deleteReview(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String postId,
            @PathVariable String reviewId
    ) {
        return gameService.deleteReview(principal.userId(), postId, reviewId);
    }
}
