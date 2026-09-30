package com.cware.ai.config;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;
@Validated
@ConfigurationProperties("app.inference")
public record InferenceProperties(
    @DecimalMin("0.0") @DecimalMax("1.0") double confidenceThreshold,
    @NotBlank String promptVersion, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    public InferenceProperties {
        if (connectTimeout != null && (connectTimeout.isNegative() || connectTimeout.isZero())
            || readTimeout != null && (readTimeout.isNegative() || readTimeout.isZero()))
            throw new IllegalArgumentException("Timeout must be positive");
    }
}
