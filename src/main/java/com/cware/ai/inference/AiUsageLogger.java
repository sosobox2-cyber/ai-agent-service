package com.cware.ai.inference;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import com.cware.ai.config.AiUsagePricing;
import com.cware.ai.dto.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;

/** API 키·프롬프트·본문 없이 호출별 usage를 독립 Logback appender에 기록한다. */
@Component
public final class AiUsageLogger implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AiUsageLogger.class);
    private final boolean enabled;
    private final AiUsagePricing pricing;
    private final ObjectWriter json;
    private final ch.qos.logback.classic.Logger fileLogger;
    private final RollingFileAppender<ILoggingEvent> appender;

    public AiUsageLogger(boolean enabled) { this(enabled, null); }
    public AiUsageLogger(boolean enabled, Path path) {
        this(enabled, path, new ObjectMapper(), new AiUsagePricing(), 30, "1GB");
    }

    @Autowired
    public AiUsageLogger(@Value("${app.ai.usage-log-enabled:false}") boolean enabled,
            @Value("${app.ai.usage-jsonl-enabled:true}") boolean jsonlEnabled,
            @Value("${app.ai.usage-jsonl-path:logs/ai-usage.jsonl}") String path,
            @Value("${app.ai.usage-jsonl-max-history:30}") int maxHistory,
            @Value("${app.ai.usage-jsonl-total-size-cap:1GB}") String totalSizeCap,
            ObjectMapper mapper, AiUsagePricing pricing) {
        this(enabled, jsonlEnabled ? safePath(path) : null, mapper, pricing, maxHistory, totalSizeCap);
    }

    public AiUsageLogger(boolean enabled, Path path, ObjectMapper mapper, AiUsagePricing pricing,
            int maxHistory, String totalSizeCap) {
        this.enabled = enabled;
        this.pricing = pricing;
        ObjectMapper logMapper = mapper.copy().disable(SerializationFeature.INDENT_OUTPUT);
        logMapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        this.json = logMapper.writer();
        ch.qos.logback.classic.Logger logger = null;
        RollingFileAppender<ILoggingEvent> sink = null;
        if (path != null) {
            try {
                LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
                // 독립 인스턴스 이름으로 테스트/다른 출력 경로의 appender 중복을 방지한다.
                logger = context.getLogger("AI_PURCHASE_OPTION_USAGE." + UUID.randomUUID());
                logger.setAdditive(false);
                logger.setLevel(ch.qos.logback.classic.Level.INFO);
                sink = new RollingFileAppender<>() {
                    @Override public void addError(String message) { reportFailure(null); }
                    @Override public void addError(String message, Throwable error) { reportFailure(error); }
                };
                sink.setContext(context);
                sink.setName("AI_PURCHASE_OPTION_USAGE");
                String absolute = path.toAbsolutePath().toString();
                sink.setFile(absolute);
                PatternLayoutEncoder encoder = new PatternLayoutEncoder();
                encoder.setContext(context);
                encoder.setCharset(StandardCharsets.UTF_8);
                encoder.setPattern("%msg%n");
                encoder.start();
                sink.setEncoder(encoder);
                TimeBasedRollingPolicy<ILoggingEvent> rolling = new TimeBasedRollingPolicy<>();
                rolling.setContext(context);
                rolling.setParent(sink);
                String stem = absolute.endsWith(".jsonl") ? absolute.substring(0, absolute.length() - 6) : absolute;
                rolling.setFileNamePattern(stem + ".%d{yyyy-MM-dd}.jsonl.gz");
                rolling.setMaxHistory(Math.max(1, maxHistory));
                rolling.setTotalSizeCap(FileSize.valueOf(totalSizeCap));
                rolling.setCleanHistoryOnStart(true);
                rolling.start();
                sink.setRollingPolicy(rolling);
                sink.start();
                logger.addAppender(sink);
            } catch (RuntimeException e) {
                if (sink != null) sink.stop();
                sink = null;
                reportFailure(e);
            }
        }
        this.fileLogger = logger;
        this.appender = sink;
    }

    private static Path safePath(String path) {
        try { return Path.of(path); }
        catch (RuntimeException e) { reportFailure(e); return null; }
    }

    private static void reportFailure(Throwable error) {
        // 예외 메시지/스택에는 입력·응답·경로가 섞일 수 있으므로 기록하지 않는다.
        LOG.warn("AI usage JSONL 기록 실패; 추론 처리는 계속합니다 ({})",
                error == null ? "appender unavailable" : error.getClass().getSimpleName());
    }

    public void recordInference(String inferenceId, String goodsId, String categoryName, PurchaseOptionPromptMode initial,
            PurchaseOptionPromptMode mode, int retryCount, List<AiRetryReason> retryReasons,
            AiInferenceValidationResult validation, String status, ChatResponse response, MappingProposal proposal,
            long elapsedMs) {
        try {
            String category = categoryName == null || categoryName.isBlank() ? null : categoryName.trim();
            if (!enabled && fileLogger == null) return;
            AiCallUsage usage = measureUsage(mode, retryCount + 1, response);
            var reasons = retryReasons == null ? List.<AiRetryReason>of() : List.copyOf(retryReasons);
            var row = new AiPurchaseOptionUsageLog(OffsetDateTime.now().toString(), inferenceId, goodsId, category,
                    mode, usage.model(), usage.input_tokens(), usage.cached_tokens(), usage.output_tokens(),
                    usage.total_tokens(), proposal == null ? null : proposal.certain(),
                    proposal == null || !ResultValidator.validConfidence(proposal.confidence()) ? null : proposal.confidence(),
                    proposal == null || proposal.mappings() == null ? null : proposal.mappings().size(),
                    elapsedMs, status, initial, "VALIDATION_ERROR".equals(status) ? PurchaseOptionPromptMode.FULL : mode,
                    retryCount, reasons.isEmpty() ? null : reasons.get(0), reasons,
                    validation == null ? null : validation.valid(), usage.estimated_cost_usd());
            String message = json.writeValueAsString(row);
            if (enabled) LOG.info("ai_usage {}", message);
            if (fileLogger == null) return;
            if (appender == null || !appender.isStarted()) { reportFailure(null); return; }
            fileLogger.info(message);
        } catch (Exception e) { reportFailure(e); }
    }

    public void record(String callId, PurchaseOptionPromptMode mode, int attempt, ChatResponse response) {
        record(callId, mode, attempt, response, null, null);
    }

    private void record(String callId, PurchaseOptionPromptMode mode, int attempt, ChatResponse response,
            String goodsId, String categoryName) {
        if (!enabled) return;
        AiCallUsage usage = measureUsage(mode, attempt, response);
        try {
            LOG.info("ai_usage call_id={} goods_id={} category_name={} mode={} attempt={} model={} input_tokens={} cached_tokens={} output_tokens={} total_tokens={} estimated_cost_usd={}",
                    callId, json.writeValueAsString(goodsId), json.writeValueAsString(categoryName),
                    usage.mode(), usage.attempt(), usage.model(), usage.input_tokens(), usage.cached_tokens(),
                    usage.output_tokens(), usage.total_tokens(), usage.estimated_cost_usd());
        } catch (Exception e) { reportFailure(e); }
    }

    public AiCallUsage measureUsage(PurchaseOptionPromptMode mode, int attempt, ChatResponse response) {
        AiCallUsage usage = measure(mode, attempt, response);
        return new AiCallUsage(usage.model(), mode, attempt, usage.input_tokens(), usage.cached_tokens(),
                usage.output_tokens(), usage.total_tokens(),
                pricing.estimate(usage.model(), usage.input_tokens(), usage.cached_tokens(), usage.output_tokens()));
    }

    public static AiCallUsage measure(PurchaseOptionPromptMode mode, int attempt, ChatResponse response) {
        var metadata = response == null ? null : response.getMetadata();
        String model = metadata == null ? "unknown" : metadata.getModel();
        if (model == null || !model.matches("(?:gpt-|o[1-9])[a-zA-Z0-9._:-]{1,95}")) model = "unknown";
        var usage = metadata == null ? null : metadata.getUsage();
        Integer input = null, output = null, total = null, cached = null;
        if (usage != null && !(usage instanceof EmptyUsage)) {
            input = usage.getPromptTokens(); output = usage.getCompletionTokens(); total = usage.getTotalTokens();
            if (usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage && nativeUsage.promptTokensDetails() != null)
                cached = nativeUsage.promptTokensDetails().cachedTokens();
        }
        return new AiCallUsage(model, mode, attempt, input, cached, output, total, null);
    }

    @PreDestroy public void close() {
        if (appender != null) {
            fileLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
