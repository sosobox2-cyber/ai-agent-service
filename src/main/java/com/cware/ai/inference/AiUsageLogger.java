package com.cware.ai.inference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import com.cware.ai.dto.AiCallUsage;

/** 요청·응답 본문, API 키, 헤더 없이 usage 수치만 기록한다. */
@Component
public final class AiUsageLogger {
    private static final Logger LOG = LoggerFactory.getLogger(AiUsageLogger.class);
    private final boolean enabled;
    public AiUsageLogger(@Value("${app.ai.usage-log-enabled:false}") boolean enabled) { this.enabled = enabled; }

    public void record(String callId, PurchaseOptionPromptMode mode, int attempt, ChatResponse response) {
        if (!enabled) return;
        AiCallUsage usage = measure(mode, attempt, response);
        LOG.info("ai_usage call_id={} mode={} attempt={} model={} input_tokens={} cached_tokens={} output_tokens={} total_tokens={} estimated_cost_usd={}",
                callId, usage.mode(), usage.attempt(), usage.model(), usage.input_tokens(), usage.cached_tokens(),
                usage.output_tokens(), usage.total_tokens(), usage.estimated_cost_usd());
    }

    public static AiCallUsage measure(PurchaseOptionPromptMode mode, int attempt, ChatResponse response) {
        var metadata = response == null ? null : response.getMetadata();
        String model = metadata == null ? "unknown" : metadata.getModel();
        // 모델명에도 임의의 응답 문자열을 로그로 통과시키지 않는다.
        if (model == null || !model.matches("(?:gpt-|o[1-9])[a-zA-Z0-9._:-]{1,95}")) model = "unknown";
        var usage = metadata == null ? null : metadata.getUsage();
        Integer input = null, output = null, total = null, cached = null;
        if (usage != null && !(usage instanceof EmptyUsage)) {
            input = usage.getPromptTokens(); output = usage.getCompletionTokens(); total = usage.getTotalTokens();
            if (usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage && nativeUsage.promptTokensDetails() != null)
                cached = nativeUsage.promptTokensDetails().cachedTokens();
        }
        return new AiCallUsage(model, mode, attempt, input, cached, output, total, estimate(model, input, cached, output));
    }

    static BigDecimal estimate(String model, Integer input, Integer cached, Integer output) {
        // 2026-10-06 공식 Standard 텍스트 단가: 1M 토큰당 input $0.40, cached $0.10, output $1.60.
        if (!model.matches("gpt-4\\.1-mini(?:-2025-04-14)?") || input == null || cached == null || output == null
                || cached < 0 || input < cached || output < 0) return null;
        return BigDecimal.valueOf(input - cached).multiply(new BigDecimal("0.40"))
                .add(BigDecimal.valueOf(cached).multiply(new BigDecimal("0.10")))
                .add(BigDecimal.valueOf(output).multiply(new BigDecimal("1.60")))
                .divide(new BigDecimal("1000000"));
    }
}
