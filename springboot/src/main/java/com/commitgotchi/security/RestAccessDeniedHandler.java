package com.commitgotchi.security;

import com.commitgotchi.common.error.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 인증은 성공했으나 Role(권한)이 부족한 요청을 처리한다.
 *
 * <p>미인증(401)은 {@link RestAuthenticationEntryPoint}가, 권한 부족(403)은 이 핸들러가 담당하며
 * 두 경로는 혼용되지 않는다. 기존 {@link SecurityErrorResponseWriter}를 재사용해
 * {@link ErrorCode#AUTH_FORBIDDEN}을 공통 {@code ErrorResponse} 형식으로 직렬화한다.
 *
 * <p>예외 메시지, stack trace, Authorization 헤더, JWT 원문을 응답이나 로그에 노출하지 않는다.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter writer;

    public RestAccessDeniedHandler(SecurityErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        writer.write(response, ErrorCode.AUTH_FORBIDDEN);
    }
}
