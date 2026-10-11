package com.cware.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Map;

/** 외부 설정에 있는 모델만 비용을 추정한다. 가격 또는 usage 누락 시 null. */
@Component
@ConfigurationProperties(prefix = "app.ai.usage-pricing")
public class AiUsagePricing {
    private Map<String, Rates> models = Map.of();
    public Map<String, Rates> getModels() { return models; }
    public void setModels(Map<String, Rates> models) { this.models = models == null ? Map.of() : Map.copyOf(models); }
    public record Rates(BigDecimal inputPerMillion, BigDecimal cachedPerMillion, BigDecimal outputPerMillion) {}

    public BigDecimal estimate(String model, Integer input, Integer cached, Integer output) {
        // 단가나 실제 사용량을 확인할 수 없으면 비용을 0으로 추정하지 않고 null로 반환한다.
        Rates rates = models.get(model);
        if (rates == null || input == null || cached == null || output == null
                || cached < 0 || input < cached || output < 0
                || rates.inputPerMillion() == null || rates.cachedPerMillion() == null || rates.outputPerMillion() == null
                || rates.inputPerMillion().signum() < 0 || rates.cachedPerMillion().signum() < 0
                || rates.outputPerMillion().signum() < 0) return null;
        // 캐시 토큰은 입력 토큰에 포함되므로 일반 입력에서 빼고 별도 캐시 단가를 적용한다.
        return BigDecimal.valueOf(input - cached).multiply(rates.inputPerMillion())
                .add(BigDecimal.valueOf(cached).multiply(rates.cachedPerMillion()))
                .add(BigDecimal.valueOf(output).multiply(rates.outputPerMillion()))
                .movePointLeft(6);
    }
}
