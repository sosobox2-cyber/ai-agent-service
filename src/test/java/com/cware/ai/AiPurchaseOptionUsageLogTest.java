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
            assertThat(row.path("goodsId").asText()).isEqualTo(Fixtures.request().goodsId());
            assertThat(row.path("categoryName").asText()).isEqualTo(Fixtures.request().categoryName());
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
            assertThat(row.has("coupangCategoryId")).isFalse();
            UUID.fromString(row.path("inferenceId").asText());
            java.time.OffsetDateTime.parse(row.path("timestamp").asText());
            assertThat(console.getAll()).doesNotContain(lines.get(0));
            if ("LIGHT".equals(mode)) Files.writeString(Path.of("target/ai-usage-log-example.jsonl"), lines.get(0) + "\n");
            var sent = org.mockito.ArgumentCaptor.forClass(Prompt.class);
            verify(model).call(sent.capture());
            String userData = sent.getValue().getInstructions().get(1).getText();
            var product = mapper.readTree(userData.substring(userData.indexOf('{'))).path("product");
            var expectedProduct = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(Fixtures.request());
            expectedProduct.remove(List.of("goodsId", "categoryName"));
            assertThat(product).isEqualTo(expectedProduct);
            assertThat(product.has("goodsId")).isFalse();
            assertThat(product.has("categoryName")).isFalse();
            assertThat(product.path("options").get(0).path("optionId").asText()).isEqualTo("1");
        }
    }

    @Test void preservesNullAndRealZeroEvenWithPrettyPrintAndNonNullApplicationMapper() throws Exception {
        Path path = temp.resolve("usage.jsonl");
        ObjectMapper applicationMapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        applicationMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        try (var logger = new AiUsageLogger(false, path, applicationMapper, new AiUsagePricing(), 30, "1GB")) {
            for (Integer cached : Arrays.asList(null, 0)) {
                logger.recordInference(UUID.randomUUID().toString(), "goods\nID", "의류\n상의", PurchaseOptionPromptMode.LIGHT,
                        PurchaseOptionPromptMode.LIGHT, 0, List.of(), null, "SUCCESS", response(cached), Fixtures.proposal(), 7);
            }
        }
        var lines = Files.readAllLines(path);
        assertThat(lines).hasSize(2);
        assertThat(mapper.readTree(lines.get(0)).path("categoryName").asText()).isEqualTo("의류\n상의");
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
        assertThat(row.path("goodsId").asText()).isEqualTo(Fixtures.request().goodsId());
        assertThat(row.path("categoryName").asText()).isEqualTo(Fixtures.request().categoryName());
        assertThat(row.path("inputTokens").isNull()).isTrue();
        assertThat(row.path("mappingCount").isNull()).isTrue();
        assertThat(lines.get(0)).doesNotContain("secret response body");
        verify(model).call(any(Prompt.class));
    }

    @Test void consoleIncludesProductAndCategoryWithoutBreakingLogLines(CapturedOutput console) throws Exception {
        Path path = temp.resolve("usage.jsonl");
        try (var logger = Fixtures.usageLogger(true, path)) {
            logger.recordInference("console-call", "goods\nID", "의류\n상의", PurchaseOptionPromptMode.FULL,
                    PurchaseOptionPromptMode.FULL, 0, List.of(), null, "SUCCESS", response(0), Fixtures.proposal(), 7);
        }
        var lines = Files.readAllLines(path);
        assertThat(lines).hasSize(1);
        assertThat(console.getOut()).contains("ai_usage " + lines.get(0))
                .contains("\"goodsId\":\"goods\\nID\"", "\"categoryName\":\"의류\\n상의\"", "\"promptMode\":\"FULL\"")
                .doesNotContain("goods\nID", "의류\n상의", "productNoticeText");
    }

    @ParameterizedTest @ValueSource(strings = {"SUCCESS", "VALIDATION_ERROR", "REVIEW_REQUIRED", "API_ERROR"})
    void consoleHasSameFullRecordEvenWithoutFileLogging(String status, CapturedOutput console) throws Exception {
        var reasons = List.of(AiRetryReason.CERTAIN_FALSE);
        var validation = new AiInferenceValidationResult(List.of(
                new AiInferenceValidationResult.ValidationError(AiRetryReason.CERTAIN_FALSE, "불확실")));
        try (var logger = Fixtures.usageLogger(true, null)) {
            logger.recordInference("console-only", "product-1", "카테고리", PurchaseOptionPromptMode.LIGHT,
                    PurchaseOptionPromptMode.FULL, 1, reasons, "API_ERROR".equals(status) ? null : validation,
                    status, "API_ERROR".equals(status) ? null : response(0),
                    "API_ERROR".equals(status) ? null : Fixtures.proposal(), 123);
        }
        var logLine = console.getOut().lines().filter(line -> line.contains("ai_usage ")).findFirst().orElseThrow();
        var row = mapper.readTree(logLine.substring(logLine.indexOf("ai_usage ") + "ai_usage ".length()));
        assertThat(row.path("goodsId").asText()).isEqualTo("product-1");
        assertThat(row.path("categoryName").asText()).isEqualTo("카테고리");
        assertThat(row.path("status").asText()).isEqualTo(status);
        assertThat(row.path("elapsedMs").asLong()).isEqualTo(123);
        assertThat(row.path("retryCount").asInt()).isEqualTo(1);
        assertThat(row.path("retryReasons").get(0).asText()).isEqualTo("CERTAIN_FALSE");
        assertThat(row.has("validationPassed")).isTrue();
        assertThat(row.has("certain")).isTrue();
        assertThat(row.has("confidence")).isTrue();
        assertThat(row.has("mappingCount")).isTrue();
        assertThat(row.has("inputTokens")).isTrue();
        assertThat(row.has("estimatedCostUsd")).isTrue();
    }

    @Test void missingAndBlankCategoriesAreLoggedAsNull() throws Exception {
        Path path = temp.resolve("usage.jsonl");
        try (var logger = Fixtures.usageLogger(false, path)) {
            for (String category : Arrays.asList(null, "", " \t\r\n ")) {
                var base = Fixtures.request();
                var request = new InferenceRequest(base.goodsId(), base.goodsName(), category,
                        base.allowedPurchaseOptions(), base.options(), base.productNoticeText());
                var model = mock(ChatModel.class);
                when(model.call(any(Prompt.class))).thenReturn(response(0));
                assertThat(service(model, logger, "FULL").infer(request)).isEqualTo(Fixtures.proposal());
            }
        }
        var lines = Files.readAllLines(path);
        assertThat(lines).hasSize(3);
        for (String line : lines) {
            assertThat(mapper.readTree(line).path("categoryName").isNull()).isTrue();
            assertThat(mapper.readTree(line).path("goodsId").asText()).isEqualTo(Fixtures.request().goodsId());
        }
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
            logger.recordInference("one", "goods", null, PurchaseOptionPromptMode.LIGHT, PurchaseOptionPromptMode.LIGHT,
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
            logger.recordInference("two", "goods", null, PurchaseOptionPromptMode.FULL, PurchaseOptionPromptMode.FULL,
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
