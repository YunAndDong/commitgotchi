package com.commitgotchi.security;

import com.commitgotchi.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/api/auth/signup",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/refresh-cookie",
            "/api/auth/logout",
            "/api/auth/logout-cookie"
    );

    private final JwtTokenProvider tokenProvider;
    private final SecurityErrorResponseWriter errorWriter;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, SecurityErrorResponseWriter errorWriter) {
        this.tokenProvider = tokenProvider;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 공개 인증 경로(refresh/logout 포함)는 Refresh Token 자체가 자격 증명이므로
        // 만료/무효 Access Token이 헤더에 실려 와도 필터가 401로 단락시키지 않는다.
        return PUBLIC_AUTH_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null
                || authorization.regionMatches(true, 0, "Internal ", 0, 9)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7) || authorization.length() == 7) {
            errorWriter.write(response, ErrorCode.AUTH_ACCESS_TOKEN_INVALID);
            return;
        }

        final AuthPrincipal principal;
        try {
            principal = tokenProvider.validate(authorization.substring(7));
        } catch (ExpiredAccessTokenException exception) {
            SecurityContextHolder.clearContext();
            errorWriter.write(response, ErrorCode.AUTH_ACCESS_TOKEN_EXPIRED);
            return;
        } catch (InvalidAccessTokenException exception) {
            SecurityContextHolder.clearContext();
            errorWriter.write(response, ErrorCode.AUTH_ACCESS_TOKEN_INVALID);
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }
}
