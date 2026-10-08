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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** 요청·응답 본문, API 키, 헤더 없이 usage 수치만 기록한다. */
@Component
public final class AiUsageLogger {
    private static final Logger LOG = LoggerFactory.getLogger(AiUsageLogger.class);
    private final boolean enabled;
    private final Path jsonlPath;
    private final ObjectMapper json = new ObjectMapper();
    public AiUsageLogger(boolean enabled) { this(enabled, null); }
    public AiUsageLogger(boolean enabled, Path jsonlPath) { this.enabled = enabled; this.jsonlPath = jsonlPath; }

    @Autowired
    public AiUsageLogger(@Value("${app.ai.usage-log-enabled:false}") boolean enabled,
            @Value("${app.ai.usage-jsonl-enabled:true}") boolean jsonlEnabled,
            @Value("${app.ai.usage-jsonl-path:logs/ai-usage.jsonl}") String jsonlPath) {
        this(enabled, jsonlEnabled ? Path.of(jsonlPath) : null);
    }

    public void recordInference(String inferenceId, String goodsId, PurchaseOptionPromptMode initial,
            PurchaseOptionPromptMode mode, int retryCount, List<AiRetryReason> retryReasons,
            AiInferenceValidationResult validation, String status, ChatResponse response, MappingProposal proposal) {
        record(inferenceId, mode, retryCount + 1, response);
        if (jsonlPath == null) return;
        var usage = measure(mode, retryCount + 1, response);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", Instant.now().toString());
        row.put("inferenceId", inferenceId);
        row.put("goodsId", goodsId);
        row.put("promptMode", mode);
        row.put("initialPromptMode", initial);
        // For a discarded LIGHT call the final mode is known to be FULL.
        row.put("finalPromptMode", "VALIDATION_ERROR".equals(status) ? PurchaseOptionPromptMode.FULL : mode);
        row.put("retryCount", retryCount);
        row.put("retryReason", retryReasons.isEmpty() ? null : retryReasons.get(0));
        row.put("retryReasons", retryReasons);
        row.put("status", status);
        row.put("validationPassed", validation == null ? null : validation.valid());
        row.put("validationErrors", validation == null ? List.of() : validation.errors());
        row.put("certain", proposal == null ? null : proposal.certain());
        row.put("confidence", proposal == null ? null : proposal.confidence());
        row.put("minimumMappingConfidence", proposal == null || proposal.mappings() == null ? null :
                proposal.mappings().stream().filter(Objects::nonNull).map(MappingProposal.Entry::confidence)
                        .filter(ResultValidator::validConfidence).min(Double::compare).orElse(null));
        row.put("model", usage.model());
        row.put("input_tokens", usage.input_tokens());
        row.put("cached_tokens", usage.cached_tokens());
        row.put("output_tokens", usage.output_tokens());
        row.put("total_tokens", usage.total_tokens());
        row.put("estimated_cost_usd", usage.estimated_cost_usd());
        append(row);
    }

    private synchronized void append(Map<String, Object> row) {
        try {
            Path absolute = jsonlPath.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            Files.writeString(absolute, json.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            LOG.warn("AI usage JSONL write failed ({})", e.getClass().getSimpleName());
        }
    }

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
