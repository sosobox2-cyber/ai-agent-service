package com.cware.ai.security;

import com.cware.ai.exception.GlobalExceptionHandler;
import com.cware.ai.exception.InferenceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Collections;

/** 현재 유일한 추론 API가 속한 /api 경로를 조기에 보호한다. 정적 페이지·문서는 공개다. */
@Configuration
public class ApiProtectionConfig {
    private final OncePerRequestFilter filter;
    public ApiProtectionConfig(@Value("${app.security.enabled:true}") boolean enabled,
            @Value("${app.security.api-key:}") String key,
            @Value("${app.security.api-key-id:internal-test}") String id,
            @Value("${app.security.rate-limit.enabled:true}") boolean rateEnabled,
            @Value("${app.security.rate-limit.per-minute:30}") int limit,
            ObjectMapper mapper, GlobalExceptionHandler errors) {
        if (enabled && (key.isBlank() || key.equals(id) || !id.matches("[A-Za-z0-9._-]{1,100}")))
            throw new IllegalArgumentException("Service API key and a valid API key ID must be configured");
        byte[] expected = digest(key);
        ApiRateLimiter limiter = new ApiRateLimiter(limit, Clock.systemUTC());
        filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String path = request.getServletPath();
                if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
                if (!(path.equals("/api") || path.startsWith("/api/") || path.startsWith("/api;"))) {
                    chain.doFilter(request, response);
                    return;
                }
                String identity = enabled ? id : "security-disabled";
                if (enabled) {
                    var headers = Collections.list(request.getHeaders("X-API-Key"));
                    if (headers.size() != 1 || headers.get(0).isBlank()
                            || !MessageDigest.isEqual(expected, digest(headers.get(0)))) {
                        reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "서비스 API Key 인증에 실패했습니다.");
                        return;
                    }
                }
                if (rateEnabled) {
                    long retryAfter = limiter.retryAfter(identity);
                    if (retryAfter > 0) {
                        response.setHeader("Retry-After", Long.toString(retryAfter));
                        reject(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "요청 한도를 초과했습니다.");
                        return;
                    }
                }
                request.setAttribute(ApiRequestIdentity.ATTRIBUTE, identity);
                chain.doFilter(request, response);
            }
            private void reject(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
                response.setStatus(status.value());
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-store");
                mapper.writeValue(response.getWriter(), errors.handle(new InferenceException(code, status, message)).getBody());
            }
        };
    }
    @Bean public FilterRegistrationBean<OncePerRequestFilter> apiProtectionFilter() {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(-100);
        registration.addUrlPatterns("/*");
        return registration;
    }
    private static byte[] digest(String key) {
        try { return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
