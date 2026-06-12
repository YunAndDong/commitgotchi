package com.commitgotchi.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 검증 응답")
public record AdminPingResponse(@Schema(example = "ok") String status) {

    public static AdminPingResponse ok() {
        return new AdminPingResponse("ok");
    }
}
