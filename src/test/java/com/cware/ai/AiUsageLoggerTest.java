package com.cware.ai;

import com.cware.ai.inference.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class AiUsageLoggerTest {
    ChatResponse response(String model, Integer cached) {
        var nativeUsage = new OpenAiApi.Usage(200, 1000, 1200,
                cached == null ? null : new OpenAiApi.Usage.PromptTokensDetails(0, cached), null);
        return new ChatResponse(List.of(), ChatResponseMetadata.builder().model(model)
                .usage(new DefaultUsage(1000, 200, 1200, nativeUsage)).build());
    }

    @Test void countsCachedTokensAsSubsetAndEstimatesCost() {
        var usage = AiUsageLogger.measure(PurchaseOptionPromptMode.LIGHT, 1, response("gpt-4.1-mini", 800));
        assertThat(usage.input_tokens()).isEqualTo(1000);
        assertThat(usage.cached_tokens()).isEqualTo(800);
        assertThat(usage.output_tokens()).isEqualTo(200);
        assertThat(usage.total_tokens()).isEqualTo(1200);
        assertThat(usage.estimated_cost_usd()).isEqualByComparingTo("0.00048");
    }

    @Test void unknownUsageCacheOrModelAreNotReportedAsZeroCost() {
        assertThat(AiUsageLogger.measure(PurchaseOptionPromptMode.FULL, 1, new ChatResponse(List.of())).input_tokens()).isNull();
        assertThat(AiUsageLogger.measure(PurchaseOptionPromptMode.FULL, 1, response("gpt-4.1-mini", null)).cached_tokens()).isNull();
        assertThat(AiUsageLogger.measure(PurchaseOptionPromptMode.FULL, 1, response("gpt-4.1-mini", null)).estimated_cost_usd()).isNull();
        assertThat(AiUsageLogger.measure(PurchaseOptionPromptMode.FULL, 1, response("other-model", 0)).estimated_cost_usd()).isNull();
    }

    @Test void disabledByDefaultAndLogsOnlyUsageWhenEnabled(CapturedOutput output) {
        new AiUsageLogger(false).record("test", PurchaseOptionPromptMode.LIGHT, 1, response("gpt-4.1-mini", 800));
        assertThat(output.getOut()).doesNotContain("ai_usage");
        new AiUsageLogger(true).record("test", PurchaseOptionPromptMode.LIGHT, 2, response("gpt-4.1-mini", 800));
        assertThat(output.getOut()).contains("mode=LIGHT", "attempt=2", "input_tokens=1000", "cached_tokens=800")
                .doesNotContain("Authorization", "Bearer", "productNoticeText", "api-key", "PLX-43UHWH");
    }
}
