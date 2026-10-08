package com.cware.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** maxRetries is constrained even when configuration is supplied externally. */
@Component
public record AiRetryProperties(boolean enabled, int maxRetries,
        boolean confidenceThresholdEnabled, double confidenceThreshold) {
    public AiRetryProperties(
            @Value("${purchase-option.ai.retry.enabled:true}") boolean enabled,
            @Value("${purchase-option.ai.retry.max-retries:1}") int maxRetries,
            @Value("${purchase-option.ai.retry.confidence-threshold-enabled:false}") boolean confidenceThresholdEnabled,
            @Value("${purchase-option.ai.retry.confidence-threshold:0.7}") double confidenceThreshold) {
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
