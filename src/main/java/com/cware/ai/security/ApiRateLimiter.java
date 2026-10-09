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
    public synchronized long retryAfter(String apiKeyId) {
        long now = clock.millis();
        Window window = windows.get(apiKeyId);
        if (window == null || now >= window.start + 60_000 || now < window.start) {
            window = new Window(now);
            windows.put(apiKeyId, window);
        }
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
