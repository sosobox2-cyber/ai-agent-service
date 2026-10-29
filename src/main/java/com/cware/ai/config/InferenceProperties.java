package com.cware.ai.config;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;
import java.util.List;
@Validated
@ConfigurationProperties("app.inference")
/**
 * 최종 자동 적용 신뢰도, 프롬프트 버전 및 AI 연결·응답 시간 제한을 묶는다.
 * reviewOnlyTargets: 이 구매옵션이 허용된 상품은 서버 검증을 통과해도 자동 적용 후보에서 제외한다.
 * 기본값은 gold150·holdout150 확정값 평가에서 오답률이 높았던 대상이다(2026-10-29 사용자 결정).
 */
public record InferenceProperties(
    @DecimalMin("0.0") @DecimalMax("1.0") double confidenceThreshold,
    @NotBlank String promptVersion, @NotNull Duration connectTimeout, @NotNull Duration readTimeout,
    @DefaultValue({"사이즈", "모델명/품번", "개당 캡슐/정", "침구류사이즈"}) List<String> reviewOnlyTargets) {
    public InferenceProperties {
        // 시간 제한은 양수여야 하며, 누락 여부는 @NotNull 검증에서 확인한다.
        if (connectTimeout != null && (connectTimeout.isNegative() || connectTimeout.isZero())
            || readTimeout != null && (readTimeout.isNegative() || readTimeout.isZero()))
            throw new IllegalArgumentException("Timeout must be positive");
        reviewOnlyTargets = reviewOnlyTargets == null ? List.of() : List.copyOf(reviewOnlyTargets);
    }
}
