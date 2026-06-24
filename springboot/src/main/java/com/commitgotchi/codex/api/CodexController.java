package com.commitgotchi.codex.api;

import com.commitgotchi.character.application.CharacterNotFoundException;
import com.commitgotchi.codex.api.dto.CodexCharacterReviewsResponse;
import com.commitgotchi.codex.api.dto.CodexCharactersPageResponse;
import com.commitgotchi.codex.api.dto.CodexRaiseCharacterResponse;
import com.commitgotchi.codex.api.dto.CodexReviewRequest;
import com.commitgotchi.codex.api.dto.CodexSpriteUrlsRequest;
import com.commitgotchi.codex.api.dto.CodexSpriteUrlsResponse;
import com.commitgotchi.codex.application.CodexCharacterService;
import com.commitgotchi.codex.application.CodexReviewService;
import com.commitgotchi.security.AuthPrincipal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/codex")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Codex")
public class CodexController {

    private final CodexCharacterService codexCharacterService;
    private final CodexReviewService codexReviewService;

    public CodexController(
            CodexCharacterService codexCharacterService,
            CodexReviewService codexReviewService
    ) {
        this.codexCharacterService = codexCharacterService;
        this.codexReviewService = codexReviewService;
    }

    @Operation(summary = "도감 카탈로그 캐릭터 목록", description = "characters 테이블의 catalog metadata만 id 오름차순으로 조회합니다.")
    @GetMapping("/characters")
    public CodexCharactersPageResponse listCharacters(
            @RequestParam(required = false) Long afterId,
            @RequestParam(required = false) Integer limit
    ) {
        return codexCharacterService.listCharacters(afterId, limit);
    }

    @Operation(summary = "도감 캐릭터 sprite URL batch 조회", description = "캐러셀 주변 캐릭터의 sprite URL만 lazy load합니다.")
    @PostMapping("/characters/sprite-urls")
    public CodexSpriteUrlsResponse spriteUrls(@Valid @RequestBody CodexSpriteUrlsRequest request) {
        return codexCharacterService.spriteUrls(request.ids());
    }

    @Operation(summary = "도감 캐릭터 리뷰 조회", description = "총 별점, 내 리뷰 상태, 다른 사용자의 리뷰 페이지를 조회합니다.")
    @GetMapping("/characters/{characterId}/reviews")
    public CodexCharacterReviewsResponse reviews(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String characterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return codexReviewService.reviews(principal.userId(), parseCharacterId(characterId), page, size);
    }

    @Operation(summary = "도감 캐릭터 리뷰 작성", description = "해당 카탈로그 캐릭터를 키운 적이 있는 사용자가 1회 작성할 수 있습니다.")
    @PostMapping("/characters/{characterId}/reviews")
    public CodexCharacterReviewsResponse createReview(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String characterId,
            @Valid @RequestBody CodexReviewRequest request
    ) {
        return codexReviewService.createReview(principal.userId(), parseCharacterId(characterId), request);
    }

    @Operation(summary = "도감 캐릭터 내 리뷰 수정", description = "작성자 본인의 도감 리뷰만 수정할 수 있습니다.")
    @PatchMapping("/characters/{characterId}/reviews/{reviewId}")
    public CodexCharacterReviewsResponse updateReview(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String characterId,
            @PathVariable String reviewId,
            @Valid @RequestBody CodexReviewRequest request
    ) {
        return codexReviewService.updateReview(
                principal.userId(),
                parseCharacterId(characterId),
                parseReviewId(reviewId),
                request
        );
    }

    @Operation(summary = "도감 캐릭터 내 리뷰 삭제", description = "작성자 본인의 도감 리뷰만 삭제할 수 있습니다.")
    @DeleteMapping("/characters/{characterId}/reviews/{reviewId}")
    public CodexCharacterReviewsResponse deleteReview(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String characterId,
            @PathVariable String reviewId
    ) {
        return codexReviewService.deleteReview(
                principal.userId(),
                parseCharacterId(characterId),
                parseReviewId(reviewId)
        );
    }

    @Operation(summary = "도감 캐릭터 키우기", description = "카탈로그 캐릭터를 현재 사용자의 user_character row로 연결해 키우기 시작합니다.")
    @PostMapping("/characters/{characterId}/raise")
    public CodexRaiseCharacterResponse raiseCharacter(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String characterId
    ) {
        return codexCharacterService.raiseCharacter(principal.userId(), parseCharacterId(characterId));
    }

    private long parseCharacterId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new CharacterNotFoundException();
        }
    }

    private long parseReviewId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid review id.");
        }
    }
}
