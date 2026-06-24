package com.commitgotchi.codex.api;

import com.commitgotchi.codex.api.dto.CodexCharactersPageResponse;
import com.commitgotchi.codex.api.dto.CodexSpriteUrlsRequest;
import com.commitgotchi.codex.api.dto.CodexSpriteUrlsResponse;
import com.commitgotchi.codex.application.CodexCharacterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
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

    public CodexController(CodexCharacterService codexCharacterService) {
        this.codexCharacterService = codexCharacterService;
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
}
