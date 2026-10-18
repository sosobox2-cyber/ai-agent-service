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
import org.springframework.beans.factory.annotation.Autowired;

/** 현재 유일한 추론 API가 속한 /api 경로를 조기에 보호한다. 정적 페이지·문서는 공개다. */
@Configuration
public class ApiProtectionConfig {
    private final OncePerRequestFilter filter;
    public ApiProtectionConfig(boolean enabled, String key, String id, boolean rateEnabled, int limit,
            ObjectMapper mapper, GlobalExceptionHandler errors) {
        this(enabled, key, id, rateEnabled, limit, mapper, errors, new LocalEvaluationAccess(false, "127.0.0.1"));
    }
    @Autowired
    public ApiProtectionConfig(@Value("${app.security.enabled:true}") boolean enabled,
            @Value("${app.security.api-key:}") String key,
            @Value("${app.security.api-key-id:internal-test}") String id,
            @Value("${app.security.rate-limit.enabled:true}") boolean rateEnabled,
            @Value("${app.security.rate-limit.per-minute:30}") int limit,
            ObjectMapper mapper, GlobalExceptionHandler errors, LocalEvaluationAccess localAccess) {
        // 인증을 켠 상태에서는 비밀 키와 로그용 식별자가 유효하게 설정되어야 시작할 수 있다.
        if (enabled && (key.isBlank() || key.equals(id) || !id.matches("[A-Za-z0-9._-]{1,100}")))
            throw new IllegalArgumentException("Service API key and a valid API key ID must be configured");
        byte[] expected = digest(key);
        ApiRateLimiter limiter = new ApiRateLimiter(limit, Clock.systemUTC());
        filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String path = request.getServletPath();
                if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
                // API 경로만 보호하고 정적 화면·문서 요청은 다음 필터로 바로 전달한다.
                if (!(path.equals("/api") || path.startsWith("/api/") || path.startsWith("/api;"))) {
                    chain.doFilter(request, response);
                    return;
                }
                boolean publicAccessInfo = request.getMethod().equals("GET") && path.equals("/api/v1/evaluation/access");
                boolean localManagement = localAccess.allows(request, path);
                String identity = localManagement ? "local-evaluation" : enabled ? id : "security-disabled";
                if (enabled && !localManagement && !publicAccessInfo) {
                    // 중복 헤더를 거부하고, 고정 길이 해시를 비교해 키 원문을 직접 비교하지 않는다.
                    var headers = Collections.list(request.getHeaders("X-API-Key"));
                    if (headers.size() != 1 || headers.get(0).isBlank()
                            || !MessageDigest.isEqual(expected, digest(headers.get(0)))) {
                        reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "서비스 API Key 인증에 실패했습니다.");
                        return;
                    }
                }
                // 평가 목록 조회·검토 저장은 AI 호출 한도를 소비하지 않는다. 인증은 동일하게 적용한다.
                boolean evaluationManagement = path.startsWith("/api/v1/evaluation/")
                        && (request.getMethod().equals("GET") || request.getMethod().equals("PUT")
                        || (request.getMethod().equals("POST") && (path.equals("/api/v1/evaluation/datasets")
                        || path.equals("/api/v1/evaluation/sample"))));
                if (rateEnabled && !evaluationManagement) {
                    // 인증된 식별자별 요청 수를 확인하며 초과 시 재요청까지 남은 초를 전달한다.
                    long retryAfter = limiter.retryAfter(identity);
                    if (retryAfter > 0) {
                        response.setHeader("Retry-After", Long.toString(retryAfter));
                        reject(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "요청 한도를 초과했습니다.");
                        return;
                    }
                }
                // 이후 사용량 로그에서는 클라이언트 키 대신 서버가 지정한 식별자를 사용한다.
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
        // 컨트롤러 처리 전에 인증과 요청 제한이 실행되도록 앞선 순서에 등록한다.
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
