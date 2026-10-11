package com.cware.ai.config;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;
@Validated
@ConfigurationProperties("app.inference")
/** 최종 자동 적용 신뢰도, 프롬프트 버전 및 AI 연결·응답 시간 제한을 묶는다. */
public record InferenceProperties(
    @DecimalMin("0.0") @DecimalMax("1.0") double confidenceThreshold,
    @NotBlank String promptVersion, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    public InferenceProperties {
        // 시간 제한은 양수여야 하며, 누락 여부는 @NotNull 검증에서 확인한다.
        if (connectTimeout != null && (connectTimeout.isNegative() || connectTimeout.isZero())
            || readTimeout != null && (readTimeout.isNegative() || readTimeout.isZero()))
            throw new IllegalArgumentException("Timeout must be positive");
    }
}
