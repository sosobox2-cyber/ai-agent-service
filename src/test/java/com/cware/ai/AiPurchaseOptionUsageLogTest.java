package com.cware.ai;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import com.cware.ai.config.*;
import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.test.system.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class AiPurchaseOptionUsageLogTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper();

    ChatResponse response(Integer cached) throws Exception {
        var nativeUsage = new OpenAiApi.Usage(200, 1000, 1200,
                cached == null ? null : new OpenAiApi.Usage.PromptTokensDetails(0, cached), null);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(mapper.writeValueAsString(Fixtures.proposal())),
                ChatGenerationMetadata.builder().finishReason("stop").build())),
                ChatResponseMetadata.builder().model("gpt-4.1-mini")
                        .usage(new DefaultUsage(1000, 200, 1200, nativeUsage)).build());
    }

    PurchaseOptionAiService service(ChatModel model, AiUsageLogger logger, String mode) throws Exception {
        return new PurchaseOptionAiService(model, mapper,
                new PurchaseOptionPromptProvider("LIGHT".equals(mode) ? "AUTO" : mode), logger);
    }

    @ParameterizedTest @ValueSource(strings = {"LIGHT", "FULL"})
    void recordsEachActualCallWithUsageResultAndElapsedTime(String mode, CapturedOutput console) throws Exception {
        Path path = temp.resolve("usage.jsonl");
        var model = mock(ChatModel.class);
        var response = response(800);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> { Thread.sleep(25); return response; });
        try (var logger = Fixtures.usageLogger(false, path)) {
            var result = service(model, logger, mode).infer(Fixtures.request());
            assertThat(result).isEqualTo(Fixtures.proposal());
            var lines = Files.readAllLines(path);
            assertThat(lines).hasSize(1);
            var row = mapper.readTree(lines.get(0));
            assertThat(row.path("promptMode").asText()).isEqualTo(mode);
            assertThat(row.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(row.path("inputTokens").asInt()).isEqualTo(1000);
            assertThat(row.path("cachedTokens").asInt()).isEqualTo(800);
            assertThat(row.path("outputTokens").asInt()).isEqualTo(200);
            assertThat(row.path("totalTokens").asInt()).isEqualTo(1200);
            assertThat(row.path("elapsedMs").asLong()).isGreaterThanOrEqualTo(20);
            assertThat(row.path("certain").asBoolean()).isTrue();
            assertThat(row.path("confidence").asDouble()).isEqualTo(.99);
            assertThat(row.path("mappingCount").asInt()).isEqualTo(Fixtures.proposal().mappings().size());
            assertThat(row.path("coupangCategoryId").isNull()).isTrue();
            UUID.fromString(row.path("inferenceId").asText());
            java.time.OffsetDateTime.parse(row.path("timestamp").asText());
            assertThat(console.getAll()).doesNotContain(lines.get(0));
            if ("LIGHT".equals(mode)) Files.writeString(Path.of("target/ai-usage-log-example.jsonl"), lines.get(0) + "\n");
            verify(model).call(any(Prompt.class));
        }
    }

    @Test void preservesNullAndRealZeroEvenWithPrettyPrintAndNonNullApplicationMapper() throws Exception {
        Path path = temp.resolve("usage.jsonl");
        ObjectMapper applicationMapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        applicationMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        try (var logger = new AiUsageLogger(false, path, applicationMapper, new AiUsagePricing(), 30, "1GB")) {
            for (Integer cached : Arrays.asList(null, 0)) {
                logger.recordInference(UUID.randomUUID().toString(), "goods\nID", PurchaseOptionPromptMode.LIGHT,
                        PurchaseOptionPromptMode.LIGHT, 0, List.of(), null, "SUCCESS", response(cached), Fixtures.proposal(), 7);
            }
        }
        var lines = Files.readAllLines(path);
        assertThat(lines).hasSize(2);
        assertThat(mapper.readTree(lines.get(0)).path("cachedTokens").isNull()).isTrue();
        assertThat(mapper.readTree(lines.get(1)).path("cachedTokens").intValue()).isZero();
        assertThat(lines.get(0)).doesNotContain("productNoticeText", "Authorization", "reason", "배기핏", "System Prompt");
    }

    @Test void apiErrorHasUnknownUsageAndPreservesOriginalFailure() throws Exception {
        Path path = temp.resolve("usage.jsonl");
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret response body"));
        try (var logger = Fixtures.usageLogger(false, path)) {
            assertThatThrownBy(() -> service(model, logger, "LIGHT").infer(Fixtures.request()))
                    .isInstanceOf(com.cware.ai.exception.InferenceException.class);
        }
        var lines = Files.readAllLines(path);
        assertThat(lines).hasSize(1);
        var row = mapper.readTree(lines.get(0));
        assertThat(row.path("status").asText()).isEqualTo("API_ERROR");
        assertThat(row.path("inputTokens").isNull()).isTrue();
        assertThat(row.path("mappingCount").isNull()).isTrue();
        assertThat(lines.get(0)).doesNotContain("secret response body");
        verify(model).call(any(Prompt.class));
    }

    @Test void filesystemFailureDoesNotChangeInference(CapturedOutput console) throws Exception {
        Path blockedParent = temp.resolve("file-not-directory");
        Files.writeString(blockedParent, "blocked");
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(0));
        try (var logger = Fixtures.usageLogger(false, blockedParent.resolve("usage.jsonl"))) {
            assertThat(service(model, logger, "LIGHT").infer(Fixtures.request())).isEqualTo(Fixtures.proposal());
        }
        assertThat(console.getAll()).contains("AI usage JSONL 기록 실패");
        verify(model).call(any(Prompt.class));
    }

    @Test void serializationFailureDoesNotChangeInference(CapturedOutput console) throws Exception {
        ObjectMapper broken = new ObjectMapper() {
            @Override public ObjectMapper copy() { return this; }
            @Override public ObjectWriter writer() {
                ObjectWriter writer = mock(ObjectWriter.class);
                try { when(writer.writeValueAsString(any())).thenThrow(new IllegalStateException("private payload")); }
                catch (Exception e) { throw new AssertionError(e); }
                return writer;
            }
        };
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(0));
        try (var logger = new AiUsageLogger(false, temp.resolve("usage.jsonl"), broken, new AiUsagePricing(), 30, "1GB")) {
            assertThat(service(model, logger, "LIGHT").infer(Fixtures.request())).isEqualTo(Fixtures.proposal());
        }
        assertThat(console.getAll()).contains("AI usage JSONL 기록 실패").doesNotContain("private payload");
    }

    @Test void dailyRolloverCreatesGzipArchiveWithThirtyDayRetention() throws Exception {
        Path path = temp.resolve("usage.jsonl");
        try (var logger = Fixtures.usageLogger(false, path)) {
            logger.recordInference("one", "goods", PurchaseOptionPromptMode.LIGHT, PurchaseOptionPromptMode.LIGHT,
                    0, List.of(), null, "SUCCESS", response(0), Fixtures.proposal(), 1);
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            RollingFileAppender<ILoggingEvent> sink = null;
            for (var candidate : context.getLoggerList()) {
                var appenders = candidate.iteratorForAppenders();
                while (appenders.hasNext()) {
                    var appender = appenders.next();
                    if (appender instanceof RollingFileAppender<?> rolling && path.toString().equals(rolling.getFile())) {
                        @SuppressWarnings("unchecked") var typed = (RollingFileAppender<ILoggingEvent>) rolling;
                        sink = typed;
                    }
                }
            }
            assertThat(sink).isNotNull();
            var policy = (TimeBasedRollingPolicy<ILoggingEvent>) sink.getRollingPolicy();
            assertThat(policy.getMaxHistory()).isEqualTo(30);
            assertThat(policy.getFileNamePattern()).endsWith(".%d{yyyy-MM-dd}.jsonl.gz");
            policy.getTimeBasedFileNamingAndTriggeringPolicy().setCurrentTime(System.currentTimeMillis() + 86_400_000);
            logger.recordInference("two", "goods", PurchaseOptionPromptMode.FULL, PurchaseOptionPromptMode.FULL,
                    0, List.of(), null, "SUCCESS", response(0), Fixtures.proposal(), 2);
            policy.stop(); // 압축 작업 종료를 기다린다.
        }
        assertThat(Files.readAllLines(path)).hasSize(1);
        try (var files = Files.list(temp)) {
            Path archive = files.filter(p -> p.toString().endsWith(".jsonl.gz")).findFirst().orElseThrow();
            try (var gzip = new java.util.zip.GZIPInputStream(Files.newInputStream(archive))) {
                assertThat(mapper.readTree(gzip).path("inferenceId").asText()).isEqualTo("one");
            }
        }
    }
}
