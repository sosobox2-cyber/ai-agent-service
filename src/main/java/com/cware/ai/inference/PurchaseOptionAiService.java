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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
/** 구조화된 AI 응답을 검증하고 LIGHT 실패 시 한 번의 FULL 재시도와 사용량 기록을 수행한다. */
public class PurchaseOptionAiService implements OptionInferenceGateway {
    private static final Logger LOG = LoggerFactory.getLogger(PurchaseOptionAiService.class);
    /** OpenAI가 max_tokens 도달로 응답을 끊었을 때의 finish_reason이다. */
    static final String TRUNCATED_FINISH_REASON = "length";
    static final String TRUNCATED_CODE = "AI_OUTPUT_TRUNCATED";
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
        // AI 응답에는 미정의 필드, 중복 키, 뒤따르는 JSON 및 타입 강제 변환을 허용하지 않는다.
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
            // 사용자 프롬프트와 상품 JSON을 결합해 최초 호출과 재시도에서 사용할 입력을 구성한다.
            String input = userPrompt + "\n" + mapper.writeValueAsString(Map.of("product", AiProductData.from(request)));
            // 최초 모드는 한 번만 선택하고, 동일한 추론 ID로 두 호출의 사용량과 로그를 연결한다.
            PurchaseOptionPromptMode initial = prompts.select(request);
            String inferenceId = UUID.randomUUID().toString();
            Attempt first = attempt(request, input, inferenceId, initial, initial, 0, List.of(), usage);
            // 최초 응답이 검증을 통과하면 추가 호출 없이 결과를 반환한다.
            if (first.validation().valid()) return first.proposal();
            // LIGHT 검증 실패 시 재시도가 활성화되고 최대 횟수가 1일 때만 FULL로 한 번 재시도한다.
            // 재귀나 반복문을 사용하지 않고, 모드 선택기를 다시 호출하지 않는다.
            if (initial == PurchaseOptionPromptMode.LIGHT && retry.enabled() && retry.maxRetries() == 1) {
                // 최초 검증 실패 사유를 재시도 기록에 전달한다.
                Attempt full = attempt(request, input, inferenceId, initial,
                        PurchaseOptionPromptMode.FULL, 1, first.validation().reasons(), usage);
                // FULL 응답도 검증에 실패하면 수동 검토가 필요한 결과로 반환한다.
                return full.validation().valid() ? full.proposal() : review(full);
            }
            // 재시도 조건에 해당하지 않으면 최초 응답을 수동 검토 대상으로 반환한다.
            return review(first);
        } catch (InferenceException e) {
            // 이미 정의된 추론 오류는 오류 코드와 HTTP 상태를 유지한다.
            throw e;
        } catch (JsonProcessingException e) {
            // 입력 JSON 직렬화 중 발생한 오류를 공통 JSON 처리 오류로 변환한다.
            throw parseFailure();
        } catch (RuntimeException e) {
            // 래핑된 원인을 순서대로 확인해 추론 오류를 보존하거나 타임아웃을 504로 변환한다.
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof InferenceException safe) throw safe;
                if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException)
                    throw new InferenceException("AI_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, "AI 응답 시간이 초과되었습니다.");
            }
            // 분류되지 않은 런타임 오류는 AI 서비스 호출 실패(502)로 변환한다.
            throw new InferenceException("AI_UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, "AI 서비스 호출에 실패했습니다.");
        }
    }

    private Attempt attempt(InferenceRequest request, String input,
            String inferenceId, PurchaseOptionPromptMode initial, PurchaseOptionPromptMode mode,
            int retryCount, List<AiRetryReason> retryReasons, Consumer<AiCallUsage> usage) {
        ChatResponse response;
        // 현재 요청과 프롬프트 모드에 맞는 JSON Schema를 지정해 응답 구조를 제한한다.
        ResponseFormat format = ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(ResponseFormat.JsonSchema.builder().name("purchase_option_mapping")
                        .strict(true).schema(schema(request, mode)).build()).build();
        // 모드별 시스템 지침과 상품 입력을 결합하고, 응답 형식 설정을 함께 전달한다.
        Prompt prompt = new Prompt(List.of(new SystemMessage(prompts.system(mode)), new UserMessage(input)),
                OpenAiChatOptions.builder().responseFormat(format).build());
        // 파싱·검증 시간을 제외한 모델 호출 소요 시간을 측정한다.
        long started = System.nanoTime();
        try {
            response = model.call(prompt);
        } catch (RuntimeException e) {
            // API 호출 실패는 응답·검증 결과 없이 기록하고 상위 예외 처리로 전달한다.
            usageLogger.recordInference(inferenceId, request.goodsId(), request.categoryName(), initial, mode, retryCount,
                    retryReasons, null, "API_ERROR", null, null, (System.nanoTime() - started) / 1_000_000);
            throw e; // 호출 오류는 LIGHT → FULL 프롬프트 재시도 대상이 아니다.
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        MappingProposal proposal = null;
        AiInferenceValidationResult validation;
        // 출력 한도 초과는 재시도해도 결과가 같으므로 사용량을 기록한 뒤 호출자에게 그대로 전달한다.
        InferenceException truncated = null;
        try {
            // 응답을 파싱하고 개수 단위를 정규화한 뒤, 요청에 맞는 매핑인지 검증한다.
            proposal = CountUnitNormalizer.normalize(request, parse(response));
            validation = validator.validate(request, proposal);
        } catch (JsonProcessingException | InferenceException e) {
            if (e instanceof InferenceException failure && TRUNCATED_CODE.equals(failure.code())) truncated = failure;
            // 원인 파악이 가능하도록 실제 finish_reason을 남긴다. 응답 본문은 기록하지 않는다.
            LOG.warn("ai_response_unusable goods_id={} mode={} attempt={} finish_reason={} code={}",
                    request.goodsId(), mode, retryCount + 1, finishReason(response),
                    truncated == null ? "AI_INVALID_JSON" : TRUNCATED_CODE);
            // 파싱 실패나 미완료 응답도 검증 실패로 변환해 동일한 재시도 판단을 적용한다.
            validation = new AiInferenceValidationResult(List.of(
                    new AiInferenceValidationResult.ValidationError(AiRetryReason.INVALID_RESPONSE,
                            truncated == null ? "AI 응답 형식이 잘못되었거나 응답이 완료되지 않았습니다." : truncated.getMessage())));
        }
        // LIGHT 검증 실패에 한해, 재시도가 활성화되고 최대 횟수가 1이면 FULL 재시도를 예정한다.
        // 출력 한도 초과는 FULL이 더 긴 응답을 요구하므로 재시도하지 않는다.
        boolean willRetry = truncated == null && !validation.valid() && mode == PurchaseOptionPromptMode.LIGHT
                && retry.enabled() && retry.maxRetries() == 1;
        // 재시도 예정이면 검증 오류, 검증·적용 신뢰도 기준을 모두 충족하면 성공, 나머지는 수동 검토로 기록한다.
        // 출력 한도 초과는 검토로 해결되지 않는 용량 문제이므로 별도 상태로 구분해 집계한다.
        String status = truncated != null ? "OUTPUT_TRUNCATED"
                : willRetry ? "VALIDATION_ERROR" : validation.valid() && validator.meetsApplicationConfidence(proposal)
                ? "SUCCESS" : "REVIEW_REQUIRED";
        // 최초 호출에는 현재 검증 실패 사유를, 재시도 호출에는 재시도를 유발한 최초 사유를 기록한다.
        usageLogger.recordInference(inferenceId, request.goodsId(), request.categoryName(), initial, mode, retryCount,
                retryCount == 0 ? validation.reasons() : retryReasons, validation, status, response, proposal, elapsedMs);
        // 이번 호출의 사용량·소요 시간을 전달하고, 재시도 예정이면 최종 모드를 FULL로 표시한다.
        usage.accept(usageLogger.measureUsage(mode, retryCount + 1, response).withInference(inferenceId,
                initial, willRetry ? PurchaseOptionPromptMode.FULL : mode, retryCount,
                retryCount == 0 ? validation.reasons() : retryReasons, status).withElapsedMs(elapsedMs));
        // 사용량을 모두 기록한 뒤에 용량 초과를 알린다. 재시도나 수동 검토로는 해결되지 않는다.
        if (truncated != null) throw truncated;
        // 상위 흐름에서 재시도 또는 수동 검토 여부를 결정할 수 있도록 제안과 검증 결과를 반환한다.
        return new Attempt(proposal, validation);
    }

    private static String finishReason(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) return "none";
        String finish = response.getResult().getMetadata().getFinishReason();
        // 공급자 문자열을 그대로 믿지 않고 알려진 짧은 토큰만 기록한다.
        return finish != null && finish.matches("[A-Za-z_]{1,32}") ? finish : "unknown";
    }

    private MappingProposal parse(ChatResponse response) throws JsonProcessingException {
        // 정상 종료된 응답만 받아 크기와 빈 본문을 확인한 뒤 엄격한 JSON 파싱을 수행한다.
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null)
            throw parseFailure();
        String finish = response.getResult().getMetadata().getFinishReason();
        // 출력 한도로 잘린 응답은 형식 오류가 아니라 용량 초과다. 같은 입력을 다시 보내도 결과가 같으므로
        // FULL 재시도 대상에서 제외하고, 단품·구매옵션 수를 줄이라는 실행 가능한 오류로 구분해 반환한다.
        if (TRUNCATED_FINISH_REASON.equalsIgnoreCase(finish)) throw outputTruncated();
        if (!"stop".equalsIgnoreCase(finish)) throw parseFailure();
        String json = response.getResult().getOutput().getText();
        if (json == null || json.isBlank() || json.length() > 65536) throw parseFailure();
        MappingProposal proposal = mapper.readValue(json, MappingProposal.class);
        if (proposal == null) throw parseFailure();
        return proposal;
    }

    private static MappingProposal review(Attempt attempt) {
        // 검증 실패 사유와 남아 있는 제안을 보존하되 certain=false로 수동 검토를 요청한다.
        MappingProposal proposal = attempt.proposal();
        String reason = "REVIEW_REQUIRED: " + attempt.validation().reasons() + "; "
                + String.join("; ", attempt.validation().errors().stream()
                        .map(AiInferenceValidationResult.ValidationError::message).toList());
        if (proposal != null && proposal.reason() != null) reason += "; AI: " + proposal.reason();
        if (reason.length() > 2000) reason = reason.substring(0, 2000);
        return new MappingProposal(false,
                proposal != null && ResultValidator.validConfidence(proposal.confidence()) ? proposal.confidence() : 0.0,
                proposal == null ? List.of() : proposal.mappings(), reason,
                proposal == null ? null : proposal.quantityEvidence());
    }

    private record Attempt(MappingProposal proposal, AiInferenceValidationResult validation) {}

    /** 매 요청의 허용 이름을 JSON Schema enum에도 주입하며 서버 검증을 별도로 수행한다. */
    public static Map<String,Object> schema(InferenceRequest request) {
        return schema(request, PurchaseOptionPromptMode.FULL);
    }

    public static Map<String,Object> schema(InferenceRequest request, PurchaseOptionPromptMode mode) {
        // 요청의 단품 ID·허용 옵션명으로 enum을 구성하고 LIGHT의 calculation은 null로 제한한다.
        // 모델은 properties 순서대로 출력하므로 대상 → 근거 → 계산 → 값 → confidence → certain 순으로 두어
        // 판단을 마친 뒤에 확신도를 매기게 한다.
        var evidence = object(Map.of("source", Map.of("type", "string", "enum",
                List.of("goodsName", "productNoticeText", "productCompositionText", "optionName1")), "text", Map.of("type", "string")),
                "source", "text");
        var operand = object(Map.of("amount", Map.of("type", "string", "description", "양수 숫자 문자열"),
                "unit", Map.of("type", "string", "description", "실제 원문 단위. 선택지 밖의 단위도 원문 그대로 보존한다."), "evidence", evidence),
                "evidence", "amount", "unit");
        var calculation = object(Map.of("operation", Map.of("type", "string", "enum",
                Arrays.stream(Calculation.Operation.values()).map(Enum::name).toList()),
                "outputUnit", Map.of("type", "string", "description",
                        "단위 설정이 없는 구매옵션은 원문과 옵션 의미에 맞는 단위를 그대로 사용한다. "
                        + "설정된 구매옵션은 해당 unitOptions 또는 defaultUnit을 사용한다."),
                "operands", Map.of("type", "array", "items", operand),
                "context", Map.of("anyOf", List.of(evidence, Map.of("type", "null")))),
                "operation", "context", "operands", "outputUnit");
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
                        : Map.of("anyOf", List.of(calculation, Map.of("type", "null")))),
                "optionId", "targetPurchaseOptionName", "evidenceSource", "evidenceText", "calculation", "value", "confidence");
        var root = new LinkedHashMap<String, Object>();
        if (mode == PurchaseOptionPromptMode.FULL && QuantityEvidenceValidator.required(request))
            root.put("quantityEvidence", QuantityEvidenceSchema.schema(request));
        root.putAll(Map.of(
                "certain", Map.of("type", "boolean"),
                "confidence", Map.of("type", "number"),
                "mappings", Map.of("type", "array", "items", entry),
                "reason", Map.of("type", "string", "description",
                        "certain 값과 관계없이 반드시 작성하는 비어 있지 않은 한국어 판단 근거 요약. "
                        + "1~3문장, 공백과 줄바꿈을 포함하여 2000자 이내. 전체 매핑 목록을 반복하지 않는다.")));
        var order = new ArrayList<String>();
        if (root.containsKey("quantityEvidence")) order.add("quantityEvidence");
        order.addAll(List.of("mappings", "reason", "confidence", "certain"));
        return stableMap(object(root, order.toArray(String[]::new)));
    }

    /** 스키마 키워드 키는 정렬하고 properties는 object()가 정한 출력 순서를 유지한다. 배열의 의미와 내용은 유지한다. */
    private static Map<String,Object> stableMap(Map<String,Object> values) {
        Map<String,Object> ordered = new LinkedHashMap<>();
        values.keySet().stream().sorted().forEach(key -> ordered.put(key, key.equals("properties")
                ? keepOrder(values.get(key)) : stableValue(values.get(key))));
        return ordered;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> keepOrder(Object properties) {
        Map<String,Object> ordered = new LinkedHashMap<>();
        ((Map<String,Object>) properties).forEach((key, value) -> ordered.put(key, stableValue(value)));
        return ordered;
    }

    @SuppressWarnings("unchecked")
    private static Object stableValue(Object value) {
        if (value instanceof Map<?,?> map) return stableMap((Map<String,Object>) map);
        if (value instanceof List<?> list) return list.stream().map(PurchaseOptionAiService::stableValue).toList();
        return value;
    }
    /** order는 모델의 출력 순서이며 properties의 모든 키를 정확히 한 번씩 나열해야 한다. */
    private static Map<String,Object> object(Map<String,Object> properties, String... order) {
        if (order.length != properties.size() || !properties.keySet().equals(Set.of(order)))
            throw new IllegalArgumentException("schema property order must list every property once: " + List.of(order));
        Map<String,Object> ordered = new LinkedHashMap<>();
        for (String key : order) ordered.put(key, properties.get(key));
        return Map.of("type","object","properties",ordered,"required",List.of(order),"additionalProperties",false);
    }
    private static InferenceException parseFailure() {
        return new InferenceException("AI_INVALID_JSON", HttpStatus.BAD_GATEWAY, "AI 응답을 정해진 JSON 형식으로 해석할 수 없습니다.");
    }

    private static InferenceException outputTruncated() {
        return new InferenceException(TRUNCATED_CODE, HttpStatus.PAYLOAD_TOO_LARGE,
                "AI 응답이 출력 한도에 도달해 잘렸습니다. 한 요청의 단품 수 또는 허용 구매옵션 수를 줄여 다시 시도하세요.");
    }
}
