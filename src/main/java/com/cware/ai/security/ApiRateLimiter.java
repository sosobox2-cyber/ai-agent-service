package com.cware.ai.security;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** 인스턴스 메모리 기반 60초 고정 window. 동시 요청의 검사와 증가를 원자적으로 수행한다. */
public final class ApiRateLimiter {
    private final int limit;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    public ApiRateLimiter(int limit, Clock clock) {
        if (limit < 1) throw new IllegalArgumentException("Rate limit must be positive");
        this.limit = limit;
        this.clock = clock;
    }
    /** 허용한 요청은 횟수를 증가시키고 0을, 초과한 요청은 남은 대기 초를 반환한다. */
    public synchronized long retryAfter(String apiKeyId) {
        long now = clock.millis();
        Window window = windows.get(apiKeyId);
        // 최초 요청, 60초 경과 또는 시계 역행 시 새 집계 구간을 시작한다.
        if (window == null || now >= window.start + 60_000 || now < window.start) {
            window = new Window(now);
            windows.put(apiKeyId, window);
        }
        // Retry-After가 너무 짧아지지 않도록 남은 밀리초를 초 단위로 올림한다.
        if (window.count >= limit) return (window.start + 60_000 - now + 999) / 1000;
        window.count++;
        return 0;
    }
    private static final class Window {
        final long start;
        int count;
        Window(long start) { this.start = start; }
    }
}
