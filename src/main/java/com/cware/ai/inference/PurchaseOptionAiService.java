package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.Calculation;
import com.cware.ai.dto.AiCallUsage;
import com.cware.ai.dto.AiProductData;
import com.cware.ai.config.AiRetryProperties;
import java.util.function.Consumer;
import com.cware.ai.exception.InferenceException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class PurchaseOptionAiService implements OptionInferenceGateway {
    private final ChatModel model;
    private final ObjectMapper mapper;
    private final PurchaseOptionPromptProvider prompts;
    private final AiUsageLogger usageLogger;
    private final String userPrompt;
    private final AiRetryProperties retry;
    private final AiInferenceValidator validator;

    public PurchaseOptionAiService(ChatModel model, ObjectMapper mapper) throws IOException {
        this(model, mapper, new PurchaseOptionPromptProvider("AUTO"), new AiUsageLogger(false));
    }

    public PurchaseOptionAiService(ChatModel model, ObjectMapper mapper,
            PurchaseOptionPromptProvider prompts, AiUsageLogger usageLogger) throws IOException {
        this(model, mapper, prompts, usageLogger, AiRetryProperties.defaults(),
                new AiInferenceValidator(new ResultValidator(), AiRetryProperties.defaults()));
    }

    @Autowired
    public PurchaseOptionAiService(ChatModel model, ObjectMapper mapper,
            PurchaseOptionPromptProvider prompts, AiUsageLogger usageLogger,
            AiRetryProperties retry, AiInferenceValidator validator) throws IOException {
        this.model = model;
        this.retry = retry;
        this.validator = validator;
        this.prompts = prompts;
        this.usageLogger = usageLogger;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.setConfig(this.mapper.getDeserializationConfig().without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        userPrompt = new ClassPathResource("prompts/coupang-purchase-option-user.txt").getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public MappingProposal infer(InferenceRequest request) {
        return infer(request, ignored -> {});
    }

    @Override
    public MappingProposal infer(InferenceRequest request, Consumer<AiCallUsage> usage) {
        try {
            String input = userPrompt + "\n" + mapper.writeValueAsString(Map.of("product", AiProductData.from(request)));
            PurchaseOptionPromptMode initial = prompts.select(request);
            String inferenceId = UUID.randomUUID().toString();
            Attempt first = attempt(request, input, inferenceId, initial, initial, 0, List.of(), usage);
            if (first.validation().valid()) return first.proposal();
            // One explicit branch: no recursion, loops, or selector invocation on failover.
            if (initial == PurchaseOptionPromptMode.LIGHT && retry.enabled() && retry.maxRetries() == 1) {
                Attempt full = attempt(request, input, inferenceId, initial,
                        PurchaseOptionPromptMode.FULL, 1, first.validation().reasons(), usage);
                return full.validation().valid() ? full.proposal() : review(full);
            }
            return review(first);
        } catch (InferenceException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw parseFailure();
        } catch (RuntimeException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof InferenceException safe) throw safe;
                if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException)
                    throw new InferenceException("AI_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, "AI 응답 시간이 초과되었습니다.");
            }
            throw new InferenceException("AI_UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, "AI 서비스 호출에 실패했습니다.");
        }
    }

    private Attempt attempt(InferenceRequest request, String input,
            String inferenceId, PurchaseOptionPromptMode initial, PurchaseOptionPromptMode mode,
            int retryCount, List<AiRetryReason> retryReasons, Consumer<AiCallUsage> usage) {
        ChatResponse response;
        ResponseFormat format = ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(ResponseFormat.JsonSchema.builder().name("purchase_option_mapping")
                        .strict(true).schema(schema(request, mode)).build()).build();
        Prompt prompt = new Prompt(List.of(new SystemMessage(prompts.system(mode)), new UserMessage(input)),
                OpenAiChatOptions.builder().responseFormat(format).build());
        long started = System.nanoTime();
        try {
            response = model.call(prompt);
        } catch (RuntimeException e) {
            usageLogger.recordInference(inferenceId, request.goodsId(), request.categoryName(), initial, mode, retryCount,
                    retryReasons, null, "API_ERROR", null, null, (System.nanoTime() - started) / 1_000_000);
            throw e; // Transport errors never trigger prompt failover.
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        MappingProposal proposal = null;
        AiInferenceValidationResult validation;
        try {
            proposal = CountUnitNormalizer.normalize(request, parse(response));
            validation = validator.validate(request, proposal);
        } catch (JsonProcessingException | InferenceException e) {
            validation = new AiInferenceValidationResult(List.of(
                    new AiInferenceValidationResult.ValidationError(AiRetryReason.INVALID_RESPONSE,
                            "AI 응답 형식이 잘못되었거나 응답이 완료되지 않았습니다.")));
        }
        boolean willRetry = !validation.valid() && mode == PurchaseOptionPromptMode.LIGHT
                && retry.enabled() && retry.maxRetries() == 1;
        String status = willRetry ? "VALIDATION_ERROR" : validation.valid() && validator.meetsApplicationConfidence(proposal)
                ? "SUCCESS" : "REVIEW_REQUIRED";
        usageLogger.recordInference(inferenceId, request.goodsId(), request.categoryName(), initial, mode, retryCount,
                retryCount == 0 ? validation.reasons() : retryReasons, validation, status, response, proposal, elapsedMs);
        usage.accept(usageLogger.measureUsage(mode, retryCount + 1, response).withInference(inferenceId,
                initial, willRetry ? PurchaseOptionPromptMode.FULL : mode, retryCount,
                retryCount == 0 ? validation.reasons() : retryReasons, status).withElapsedMs(elapsedMs));
        return new Attempt(proposal, validation);
    }

    private MappingProposal parse(ChatResponse response) throws JsonProcessingException {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null)
            throw parseFailure();
        String finish = response.getResult().getMetadata().getFinishReason();
        if (!"stop".equalsIgnoreCase(finish)) throw parseFailure();
        String json = response.getResult().getOutput().getText();
        if (json == null || json.isBlank() || json.length() > 65536) throw parseFailure();
        MappingProposal proposal = mapper.readValue(json, MappingProposal.class);
        if (proposal == null) throw parseFailure();
        return proposal;
    }

    private static MappingProposal review(Attempt attempt) {
        MappingProposal proposal = attempt.proposal();
        String reason = "REVIEW_REQUIRED: " + attempt.validation().reasons() + "; "
                + String.join("; ", attempt.validation().errors().stream()
                        .map(AiInferenceValidationResult.ValidationError::message).toList());
        if (proposal != null && proposal.reason() != null) reason += "; AI: " + proposal.reason();
        if (reason.length() > 2000) reason = reason.substring(0, 2000);
        return new MappingProposal(false,
                proposal != null && ResultValidator.validConfidence(proposal.confidence()) ? proposal.confidence() : 0.0,
                proposal == null ? List.of() : proposal.mappings(), reason);
    }

    private record Attempt(MappingProposal proposal, AiInferenceValidationResult validation) {}

    /** 매 요청의 허용 이름을 JSON Schema enum에도 주입하며 서버 검증을 별도로 수행한다. */
    public static Map<String,Object> schema(InferenceRequest request) {
        return schema(request, PurchaseOptionPromptMode.FULL);
    }

    public static Map<String,Object> schema(InferenceRequest request, PurchaseOptionPromptMode mode) {
        var evidence = object(Map.of("source", Map.of("type", "string", "enum",
                List.of("goodsName", "productNoticeText", "productCompositionText", "optionName1")), "text", Map.of("type", "string")));
        var operand = object(Map.of("amount", Map.of("type", "string", "description", "양수 숫자 문자열"),
                "unit", Map.of("type", "string", "description", "실제 원문 단위. 선택지 밖의 단위도 원문 그대로 보존한다."), "evidence", evidence));
        var calculation = object(Map.of("operation", Map.of("type", "string", "enum",
                Arrays.stream(Calculation.Operation.values()).map(Enum::name).toList()),
                "outputUnit", Map.of("type", "string", "description",
                        "단위 설정이 없는 구매옵션은 원문과 옵션 의미에 맞는 단위를 그대로 사용한다. "
                        + "설정된 구매옵션은 해당 unitOptions 또는 defaultUnit을 사용한다."),
                "operands", Map.of("type", "array", "items", operand),
                "context", Map.of("anyOf", List.of(evidence, Map.of("type", "null")))));
        Map<String,Object> entry = object(Map.of(
                "optionId", Map.of("type", "string", "enum", request.options().stream()
                        .map(option -> option.optionId()).toList()),
                "targetPurchaseOptionName", Map.of("type", "string", "enum", request.allowedPurchaseOptions()),
                "value", Map.of("type", "string"),
                "confidence", Map.of("type", "number"),
                "evidenceSource", Map.of("type", List.of("string", "null"), "enum",
                        Arrays.asList("goodsName", "productNoticeText", "productCompositionText", null)),
                "evidenceText", Map.of("type", List.of("string", "null"), "description",
                        "실제 원문 근거 요약. 일반 옵션명 추출은 null."),
                "calculation", mode == PurchaseOptionPromptMode.LIGHT ? Map.of("type", "null")
                        : Map.of("anyOf", List.of(calculation, Map.of("type", "null")))));
        return stableMap(object(Map.of(
                "certain", Map.of("type", "boolean"),
                "confidence", Map.of("type", "number"),
                "mappings", Map.of("type", "array", "items", entry),
                "reason", Map.of("type", "string", "description",
                        "certain 값과 관계없이 반드시 작성하는 비어 있지 않은 한국어 판단 근거 요약. "
                        + "1~3문장, 공백과 줄바꿈을 포함하여 2000자 이내. 전체 매핑 목록을 반복하지 않는다."))));
    }

    /** 객체 키만 정렬한다. enum·anyOf·required 등의 배열 의미와 내용은 유지한다. */
    private static Map<String,Object> stableMap(Map<String,Object> values) {
        Map<String,Object> ordered = new LinkedHashMap<>();
        values.keySet().stream().sorted().forEach(key -> ordered.put(key, stableValue(values.get(key))));
        return ordered;
    }

    @SuppressWarnings("unchecked")
    private static Object stableValue(Object value) {
        if (value instanceof Map<?,?> map) return stableMap((Map<String,Object>) map);
        if (value instanceof List<?> list) return list.stream().map(PurchaseOptionAiService::stableValue).toList();
        return value;
    }
    private static Map<String,Object> object(Map<String,Object> properties) {
        return Map.of("type","object","properties",properties,"required",properties.keySet().stream().sorted().toList(),
                "additionalProperties",false);
    }
    private static InferenceException parseFailure() {
        return new InferenceException("AI_INVALID_JSON", HttpStatus.BAD_GATEWAY, "AI 응답을 정해진 JSON 형식으로 해석할 수 없습니다.");
    }
}
