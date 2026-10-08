package com.cware.ai;

import com.cware.ai.config.AiRetryProperties;
import com.cware.ai.dto.*;
import com.cware.ai.exception.InferenceException;
import com.cware.ai.inference.*;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiFailoverTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper();
    final ChatModel model = mock(ChatModel.class);
    final InferenceRequest request = new InferenceRequest("68535109", "니트", "브랜드", "의류", "1", "의류",
            List.of("색상", "패션의류/잡화 사이즈"), List.of(new SourceOption("1", "블랙/90"),
            new SourceOption("2", "블랙/95"), new SourceOption("3", "블랙/100"), new SourceOption("4", "블랙/105")), null);

    static MappingProposal good() {
        List<MappingProposal.Entry> entries = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            entries.add(new MappingProposal.Entry("" + i, "색상", "블랙", .95));
            entries.add(new MappingProposal.Entry("" + i, "패션의류/잡화 사이즈", "" + (85 + i * 5), .95));
        }
        return new MappingProposal(true, .95, entries, "원문 색상과 사이즈 추출");
    }

    ChatResponse response(MappingProposal proposal) throws Exception {
        var nativeUsage = new OpenAiApi.Usage(200, 1000, 1200, new OpenAiApi.Usage.PromptTokensDetails(0, 800), null);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(mapper.writeValueAsString(proposal)),
                ChatGenerationMetadata.builder().finishReason("stop").build())),
                ChatResponseMetadata.builder().model("gpt-4.1-mini")
                        .usage(new DefaultUsage(1000, 200, 1200, nativeUsage)).build());
    }

    PurchaseOptionInferenceService service(String mode, AiRetryProperties settings) throws Exception {
        var provider = new PurchaseOptionPromptProvider(mode);
        var ai = new PurchaseOptionAiService(model, mapper, provider, new AiUsageLogger(false, temp.resolve("usage.jsonl")),
                settings, new AiInferenceValidator(new ResultValidator(), settings));
        return new PurchaseOptionInferenceService(new RequestValidator(), ai, new ResultValidator(),
                new InputHashService(mapper), Fixtures.properties());
    }

    @Test void normalLightCallsOnceWithZeroRetries() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response(good()));
        var result = service("AUTO", AiRetryProperties.defaults()).infer(request);
        assertThat(result.success()).isTrue();
        assertThat(result.aiUsage()).singleElement().satisfies(call -> {
            assertThat(call.mode()).isEqualTo(PurchaseOptionPromptMode.LIGHT);
            assertThat(call.retryCount()).isZero();
        });
        verify(model).call(any(Prompt.class));
        assertThat(Files.readAllLines(temp.resolve("usage.jsonl"))).hasSize(1);
    }

    static Stream<Arguments> failures() {
        var good = good();
        var missing = good.mappings().subList(0, 6);
        var invalid = new ArrayList<>(good.mappings());
        invalid.set(0, new MappingProposal.Entry("1", "수량", "1", .95));
        var unknown = new ArrayList<>(good.mappings());
        unknown.add(new MappingProposal.Entry("999", "색상", "블랙", .95));
        var duplicate = new ArrayList<>(good.mappings());
        duplicate.add(good.mappings().get(0));
        var blank = new ArrayList<>(good.mappings());
        blank.set(0, new MappingProposal.Entry("1", "색상", " ", .95));
        return Stream.of(Arguments.of(new MappingProposal(false, .95, good.mappings(), "불확실"), AiRetryReason.CERTAIN_FALSE),
                Arguments.of(new MappingProposal(true, .95, missing, "누락"), AiRetryReason.MISSING_OPTION_MAPPING),
                Arguments.of(new MappingProposal(true, .95, invalid, "허용되지 않음"), AiRetryReason.INVALID_PURCHASE_OPTION),
                Arguments.of(new MappingProposal(true, .95, unknown, "없는 ID"), AiRetryReason.UNKNOWN_OPTION_ID),
                Arguments.of(new MappingProposal(true, .95, duplicate, "중복"), AiRetryReason.DUPLICATE_MAPPING),
                Arguments.of(new MappingProposal(true, .95, blank, "빈 값"), AiRetryReason.EMPTY_VALUE));
    }

    @ParameterizedTest @MethodSource("failures")
    void invalidLightFailsOverToExplicitFullOnce(MappingProposal invalid, AiRetryReason reason) throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response(invalid), response(good()));
        var result = service("AUTO", AiRetryProperties.defaults()).infer(request);
        assertThat(result.success()).isTrue();
        assertThat(result.aiUsage()).extracting(AiCallUsage::retryCount).containsExactly(0, 1);
        assertThat(result.aiUsage().get(1).retryReasons()).contains(reason);
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        var calls = prompts.getAllValues();
        var provider = new PurchaseOptionPromptProvider("AUTO");
        assertThat(calls.get(0).getInstructions().get(0).getText()).isEqualTo(provider.system(PurchaseOptionPromptMode.LIGHT));
        assertThat(calls.get(1).getInstructions().get(0).getText()).isEqualTo(provider.system(PurchaseOptionPromptMode.FULL));
        assertThat(calls.get(1).getInstructions()).hasSize(2);
        assertThat(calls.get(0).getInstructions().get(1).getText()).isEqualTo(calls.get(1).getInstructions().get(1).getText());
        com.fasterxml.jackson.databind.JsonNode firstOptions = mapper.valueToTree(calls.get(0).getOptions());
        com.fasterxml.jackson.databind.JsonNode fullOptions = mapper.valueToTree(calls.get(1).getOptions());
        assertThat(firstOptions).isEqualTo(fullOptions);
        var lines = Files.readAllLines(temp.resolve("usage.jsonl"));
        assertThat(lines).hasSize(2);
        var light = mapper.readTree(lines.get(0));
        var full = mapper.readTree(lines.get(1));
        assertThat(light.path("status").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(full.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(full.path("inferenceId")).isEqualTo(light.path("inferenceId"));
        UUID.fromString(full.path("inferenceId").asText());
        assertThat(full.path("initialPromptMode").asText()).isEqualTo("LIGHT");
        assertThat(full.path("finalPromptMode").asText()).isEqualTo("FULL");
        assertThat(full.path("retryReason").asText()).isEqualTo(reason.name());
        assertThat(full.path("input_tokens").asInt() + light.path("input_tokens").asInt()).isEqualTo(2000);
        assertThat(result.aiUsage().stream().map(AiCallUsage::estimated_cost_usd)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)).isEqualByComparingTo("0.00096");
    }

    @Test void fullFailureRequiresReviewAndNeverCallsThirdTime() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response(new MappingProposal(false, .95, good().mappings(), "불확실")));
        var result = service("AUTO", AiRetryProperties.defaults()).infer(request);
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.aiAssessment().certain()).isFalse();
        assertThat(result.reason()).contains("CERTAIN_FALSE");
        assertThat(result.items()).isEmpty();
        assertThat(result.aiUsage()).extracting(AiCallUsage::retryCount).containsExactly(0, 1);
        verify(model, times(2)).call(any(Prompt.class));
        assertThat(mapper.readTree(Files.readAllLines(temp.resolve("usage.jsonl")).get(1)).path("status").asText())
                .isEqualTo("REVIEW_REQUIRED");
    }

    @Test void initialFullNeverFailsOverEvenForMissingMappings() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response(new MappingProposal(true, .95, List.of(), "누락")));
        var result = service("FULL", AiRetryProperties.defaults()).infer(request);
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.aiUsage()).singleElement().satisfies(call -> assertThat(call.retryCount()).isZero());
        verify(model).call(any(Prompt.class));
    }

    @Test void selectorChosenFullProductNeverFailsOver() throws Exception {
        var fullProduct = new InferenceRequest(request.goodsId(), request.goodsName(), request.brand(),
                request.categoryName(), request.coupangCategoryId(), request.coupangCategoryName(),
                List.of("수량"), request.options(), null);
        when(model.call(any(Prompt.class))).thenReturn(response(new MappingProposal(true, .95, List.of(), "누락")));
        var result = service("AUTO", AiRetryProperties.defaults()).infer(fullProduct);
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.aiUsage().get(0).initialPromptMode()).isEqualTo(PurchaseOptionPromptMode.FULL);
        verify(model).call(any(Prompt.class));
    }

    @Test void fullApiFailurePreservesLightUsageInJsonlAndDoesNotCallAgain() throws Exception {
        when(model.call(any(Prompt.class)))
                .thenReturn(response(new MappingProposal(false, .95, good().mappings(), "불확실")))
                .thenThrow(new InferenceException("AI_UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, "실패"));
        var service = service("AUTO", AiRetryProperties.defaults());
        assertThatThrownBy(() -> service.infer(request)).isInstanceOfSatisfying(InferenceException.class,
                e -> assertThat(e.code()).isEqualTo("AI_UPSTREAM_ERROR"));
        verify(model, times(2)).call(any(Prompt.class));
        var lines = Files.readAllLines(temp.resolve("usage.jsonl"));
        assertThat(lines).hasSize(2);
        var light = mapper.readTree(lines.get(0));
        var full = mapper.readTree(lines.get(1));
        assertThat(light.path("input_tokens").asInt()).isEqualTo(1000);
        assertThat(full.path("input_tokens").isNull()).isTrue();
        assertThat(full.path("status").asText()).isEqualTo("API_ERROR");
        assertThat(full.path("inferenceId")).isEqualTo(light.path("inferenceId"));
    }

    @Test void malformedLightCanRecoverUsingValidFull() throws Exception {
        var malformed = new ChatResponse(List.of(new Generation(new AssistantMessage("not json"),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
        when(model.call(any(Prompt.class))).thenReturn(malformed, response(good()));
        var result = service("AUTO", AiRetryProperties.defaults()).infer(request);
        assertThat(result.success()).isTrue();
        assertThat(result.aiUsage().get(1).retryReasons()).containsExactly(AiRetryReason.INVALID_RESPONSE);
        verify(model, times(2)).call(any(Prompt.class));
    }

    @ParameterizedTest @ValueSource(strings = {"timeout", "429", "5xx"})
    void apiFailuresDoNotTriggerFull(String type) throws Exception {
        RuntimeException failure = switch (type) {
            case "timeout" -> new ResourceAccessException("private", new SocketTimeoutException());
            case "429" -> new InferenceException("AI_RATE_LIMIT", HttpStatus.TOO_MANY_REQUESTS, "한도");
            default -> new InferenceException("AI_UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, "실패");
        };
        when(model.call(any(Prompt.class))).thenThrow(failure);
        var service = service("AUTO", AiRetryProperties.defaults());
        assertThatThrownBy(() -> service.infer(request)).isInstanceOfSatisfying(InferenceException.class,
                e -> assertThat(e.code()).isEqualTo(type.equals("timeout") ? "AI_TIMEOUT" : type.equals("429") ? "AI_RATE_LIMIT" : "AI_UPSTREAM_ERROR"));
        verify(model).call(any(Prompt.class));
        var lines = Files.readAllLines(temp.resolve("usage.jsonl"));
        assertThat(lines).hasSize(1);
        assertThat(mapper.readTree(lines.get(0)).path("status").asText()).isEqualTo("API_ERROR");
    }

    @Test void lowConfidenceAloneDoesNotFailOverByDefault() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response(new MappingProposal(true, .5, good().mappings(), "낮은 신뢰도")));
        var result = service("AUTO", AiRetryProperties.defaults()).infer(request);
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED"); // Existing final approval threshold remains.
        assertThat(result.aiUsage()).hasSize(1);
        assertThat(result.aiUsage().get(0).status()).isEqualTo("REVIEW_REQUIRED");
        verify(model).call(any(Prompt.class));
    }

    @Test void optionalThresholdTriggersFailover() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response(new MappingProposal(true, .5, good().mappings(), "낮은 신뢰도")), response(good()));
        var result = service("AUTO", new AiRetryProperties(true, 1, true, .7)).infer(request);
        assertThat(result.success()).isTrue();
        assertThat(result.aiUsage().get(1).retryReasons()).contains(AiRetryReason.LOW_CONFIDENCE);
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test void retryCanBeDisabledAndUnsafeLimitsAreRejected() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response(new MappingProposal(false, .95, good().mappings(), "불확실")));
        assertThat(service("AUTO", new AiRetryProperties(false, 1, false, .7)).infer(request).errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(service("AUTO", new AiRetryProperties(true, 0, false, .7)).infer(request).errorCode()).isEqualTo("REVIEW_REQUIRED");
        verify(model, times(2)).call(any(Prompt.class));
        assertThatThrownBy(() -> new AiRetryProperties(true, 2, false, .7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiRetryProperties(true, -1, false, .7)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void selectorIsUsedOnlyOnce() throws Exception {
        var provider = mock(PurchaseOptionPromptProvider.class);
        when(provider.select(request)).thenReturn(PurchaseOptionPromptMode.LIGHT);
        when(provider.system(any())).thenReturn("system");
        when(model.call(any(Prompt.class))).thenReturn(response(new MappingProposal(false, .95, good().mappings(), "불확실")), response(good()));
        var ai = new PurchaseOptionAiService(model, mapper, provider, new AiUsageLogger(false));
        assertThat(ai.infer(request)).isEqualTo(good());
        verify(provider).select(request);
        verify(provider).system(PurchaseOptionPromptMode.FULL);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void allRequiredMappingFieldsRejectNullAndBlank(String empty) {
        var validator = new AiInferenceValidator(new ResultValidator(), AiRetryProperties.defaults());
        for (var entry : List.of(new MappingProposal.Entry(empty, "색상", "블랙", .95),
                new MappingProposal.Entry("1", empty, "블랙", .95), new MappingProposal.Entry("1", "색상", empty, .95))) {
            var result = validator.validate(request, new MappingProposal(true, .95, List.of(entry), "사유"));
            assertThat(result.valid()).isFalse();
            assertThat(result.reasons()).contains(AiRetryReason.EMPTY_VALUE);
        }
    }
}
