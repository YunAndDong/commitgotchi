package com.commitgotchi.game.api;

import com.commitgotchi.game.api.dto.GameMutationResponse;
import com.commitgotchi.game.api.dto.GameStateResponse;
import com.commitgotchi.game.application.GameService;
import com.commitgotchi.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
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
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/state")
    public GameStateResponse state(@AuthenticationPrincipal AuthPrincipal principal) {
        return new GameStateResponse(gameService.state(principal.userId()));
    }

    @PostMapping("/characters")
    public GameMutationResponse createCharacter(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody JsonNode request
    ) {
        return gameService.createCharacter(principal.userId(), request);
    }

    @PatchMapping("/characters/{id}")
    public GameMutationResponse updateCharacter(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @RequestBody JsonNode request
    ) {
        return gameService.updateCharacter(principal.userId(), id, request);
    }

    @PatchMapping("/characters/{id}/active")
    public GameMutationResponse setActiveCharacter(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id
    ) {
        return gameService.setActiveCharacter(principal.userId(), id);
    }

    @PostMapping("/characters/{id}/retry-image")
    public GameMutationResponse retryImage(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id
    ) {
        return gameService.retryImage(principal.userId(), id);
    }

    @DeleteMapping("/characters/{id}")
    public GameMutationResponse deleteCharacter(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id
    ) {
        return gameService.deleteCharacter(principal.userId(), id);
    }

    @PostMapping("/reports")
    public GameMutationResponse saveReport(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody JsonNode request
    ) {
        return gameService.saveReport(principal.userId(), request);
    }

    @PostMapping("/quizzes/{id}/submit")
    public GameMutationResponse submitQuiz(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @RequestBody JsonNode request
    ) {
        return gameService.submitQuiz(principal.userId(), id, request);
    }

    @PostMapping("/daily-report/deliver")
    public GameMutationResponse deliverDailyReport(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody(required = false) JsonNode request
    ) {
        return gameService.deliverDailyReport(principal.userId(), request);
    }

    @PostMapping("/board-posts")
    public GameMutationResponse createBoardPost(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody JsonNode request
    ) {
        return gameService.createBoardPost(principal.userId(), request);
    }

    @PatchMapping("/board-posts/{id}")
    public GameMutationResponse updateBoardPost(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @RequestBody JsonNode request
    ) {
        return gameService.updateBoardPost(principal.userId(), id, request);
    }

    @DeleteMapping("/board-posts/{id}")
    public GameMutationResponse deleteBoardPost(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id
    ) {
        return gameService.deleteBoardPost(principal.userId(), id);
    }

    @PostMapping("/board-posts/{postId}/reviews")
    public GameMutationResponse addReview(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String postId,
            @RequestBody JsonNode request
    ) {
        return gameService.addReview(principal.userId(), postId, request);
    }

    @PatchMapping("/board-posts/{postId}/reviews/{reviewId}")
    public GameMutationResponse updateReview(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String postId,
            @PathVariable String reviewId,
            @RequestBody JsonNode request
    ) {
        return gameService.updateReview(principal.userId(), postId, reviewId, request);
    }

    @DeleteMapping("/board-posts/{postId}/reviews/{reviewId}")
    public GameMutationResponse deleteReview(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String postId,
            @PathVariable String reviewId
    ) {
        return gameService.deleteReview(principal.userId(), postId, reviewId);
    }
}
