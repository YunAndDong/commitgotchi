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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class InternalApiAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHORITY = "INTERNAL_API";

    private final InternalApiProperties properties;
    private final SecurityErrorResponseWriter errorWriter;

    public InternalApiAuthenticationFilter(
            InternalApiProperties properties,
            SecurityErrorResponseWriter errorWriter
    ) {
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("/api/report".equals(path) || path.startsWith("/api/internal/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            errorWriter.write(response, ErrorCode.AUTH_ACCESS_TOKEN_MISSING);
            return;
        }
        if (!authorization.regionMatches(true, 0, "Internal ", 0, 9) || authorization.length() == 9) {
            errorWriter.write(response, ErrorCode.AUTH_ACCESS_TOKEN_INVALID);
            return;
        }

        String candidate = authorization.substring(9).strip();
        if (!matchesSecret(candidate)) {
            SecurityContextHolder.clearContext();
            errorWriter.write(response, ErrorCode.AUTH_ACCESS_TOKEN_INVALID);
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                "internal-api",
                null,
                List.of(new SimpleGrantedAuthority(AUTHORITY))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private boolean matchesSecret(String candidate) {
        if (!properties.hasSecret() || candidate.isBlank()) {
            return false;
        }
        byte[] expected = properties.normalizedSecret().getBytes(StandardCharsets.UTF_8);
        byte[] actual = candidate.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
