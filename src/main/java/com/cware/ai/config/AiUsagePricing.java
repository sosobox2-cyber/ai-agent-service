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
        Rates rates = models.get(model);
        if (rates == null || input == null || cached == null || output == null
                || cached < 0 || input < cached || output < 0
                || rates.inputPerMillion() == null || rates.cachedPerMillion() == null || rates.outputPerMillion() == null
                || rates.inputPerMillion().signum() < 0 || rates.cachedPerMillion().signum() < 0
                || rates.outputPerMillion().signum() < 0) return null;
        return BigDecimal.valueOf(input - cached).multiply(rates.inputPerMillion())
                .add(BigDecimal.valueOf(cached).multiply(rates.cachedPerMillion()))
                .add(BigDecimal.valueOf(output).multiply(rates.outputPerMillion()))
                .movePointLeft(6);
    }
}
