package com.cware.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** LIGHT → FULL 재시도 설정. 외부 설정에서도 최대 재시도 횟수를 0 또는 1로 제한한다. */
@Component
public record AiRetryProperties(boolean enabled, int maxRetries,
        boolean confidenceThresholdEnabled, double confidenceThreshold) {
    public AiRetryProperties(
            @Value("${purchase-option.ai.retry.enabled:true}") boolean enabled,
            @Value("${purchase-option.ai.retry.max-retries:1}") int maxRetries,
            @Value("${purchase-option.ai.retry.confidence-threshold-enabled:false}") boolean confidenceThresholdEnabled,
            @Value("${purchase-option.ai.retry.confidence-threshold:0.7}") double confidenceThreshold) {
        // 잘못된 설정으로 반복 호출하거나 유효하지 않은 신뢰도 기준을 적용하지 않도록 시작 시 검증한다.
        if (maxRetries < 0 || maxRetries > 1) throw new IllegalArgumentException("max-retries must be 0 or 1");
        if (!Double.isFinite(confidenceThreshold) || confidenceThreshold < 0 || confidenceThreshold > 1)
            throw new IllegalArgumentException("confidence-threshold must be between 0 and 1");
        this.enabled = enabled;
        this.maxRetries = maxRetries;
        this.confidenceThresholdEnabled = confidenceThresholdEnabled;
        this.confidenceThreshold = confidenceThreshold;
    }

    public static AiRetryProperties defaults() { return new AiRetryProperties(true, 1, false, .7); }
}
