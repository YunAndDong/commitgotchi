package com.commitgotchi.character.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CharacterCreateRequest(
        @Schema(description = "캐릭터 표시 이름. 공백일 수 없고 최대 40자입니다.", example = "Commit Buddy")
        @NotBlank
        @Size(max = 40)
        String name,

        @Schema(description = "캐릭터 이미지와 성향을 만드는 디자인 키워드. 공백일 수 없고 최대 120자입니다.", example = "green study slime")
        @NotBlank
        @Size(max = 120)
        String keyword,

        @Schema(description = "캐릭터 성격 설명. 공백일 수 없고 최대 500자입니다.", example = "Kind but precise")
        @NotBlank
        @Size(max = 500)
        String personality
) {
}
