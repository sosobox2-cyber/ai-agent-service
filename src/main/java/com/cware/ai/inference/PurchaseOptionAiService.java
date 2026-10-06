package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.Calculation;
import com.cware.ai.dto.AiCallUsage;
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

    public PurchaseOptionAiService(ChatModel model, ObjectMapper mapper) throws IOException {
        this(model, mapper, new PurchaseOptionPromptProvider("AUTO"), new AiUsageLogger(false));
    }

    @Autowired
    public PurchaseOptionAiService(ChatModel model, ObjectMapper mapper,
            PurchaseOptionPromptProvider prompts, AiUsageLogger usageLogger) throws IOException {
        this.model = model;
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
            String input = userPrompt + "\n" + mapper.writeValueAsString(Map.of("product", request));
            ResponseFormat format = ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(ResponseFormat.JsonSchema.builder().name("purchase_option_mapping")
                            .strict(true).schema(schema(request)).build()).build();
            PurchaseOptionPromptMode mode = prompts.select(request);
            String callId = UUID.randomUUID().toString();
            List<Message> messages = new ArrayList<>(List.of(new SystemMessage(prompts.system(mode)), new UserMessage(input)));
            MappingProposal proposal = call(messages, format, callId, mode, 1, usage);
            List<String> missing = missingOptionIds(request, proposal);
            if (Boolean.TRUE.equals(proposal.certain()) && !missing.isEmpty()) {
                messages.add(new AssistantMessage(mapper.writeValueAsString(proposal)));
                messages.add(new UserMessage(
                        "직전 응답에서 다음 optionId의 구매옵션 매핑이 누락되었습니다: "
                        + mapper.writeValueAsString(missing)
                        + ". product.options 전체를 다시 검토하고 기존 단품을 포함한 전체 mappings를 반환하세요. "
                        + "누락된 단품만 반환하거나 값을 임의로 복사하지 마세요. "
                        + "단품별로 근거가 있는 허용 구매옵션을 추출하고, 판단할 수 없는 단품이 있으면 "
                        + "certain=false로 반환하며 reason에 해당 ID와 이유를 설명하세요."));
                proposal = call(messages, format, callId, mode, 2, usage);
            }
            return proposal;
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

    private MappingProposal call(List<Message> messages, ResponseFormat format, String callId,
            PurchaseOptionPromptMode mode, int attempt, Consumer<AiCallUsage> usage) throws JsonProcessingException {
        ChatResponse response = model.call(new Prompt(List.copyOf(messages),
                OpenAiChatOptions.builder().responseFormat(format).build()));
        usageLogger.record(callId, mode, attempt, response);
        usage.accept(AiUsageLogger.measure(mode, attempt, response));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null)
            throw parseFailure();
        String finish = response.getResult().getMetadata().getFinishReason();
        if (!"stop".equalsIgnoreCase(finish))
            throw new InferenceException("AI_INCOMPLETE_RESPONSE", HttpStatus.BAD_GATEWAY, "AI 응답이 정상적으로 완료되지 않았습니다.");
        String json = response.getResult().getOutput().getText();
        if (json == null || json.isBlank() || json.length() > 65536) throw parseFailure();
        MappingProposal proposal = mapper.readValue(json, MappingProposal.class);
        if (proposal == null) throw parseFailure();
        return proposal;
    }

    private static List<String> missingOptionIds(InferenceRequest request, MappingProposal proposal) {
        Set<String> returned = new HashSet<>();
        if (proposal.mappings() != null) {
            for (MappingProposal.Entry entry : proposal.mappings()) {
                if (entry != null) returned.add(entry.optionId());
            }
        }
        return request.options().stream().map(option -> option.optionId())
                .filter(id -> !returned.contains(id)).toList();
    }

    /** 매 요청의 허용 이름을 JSON Schema enum에도 주입하며 서버 검증을 별도로 수행한다. */
    public static Map<String,Object> schema(InferenceRequest request) {
        var evidence = object(Map.of("source", Map.of("type", "string", "enum",
                List.of("goodsName", "productNoticeText", "optionName1")), "text", Map.of("type", "string")));
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
                        Arrays.asList("goodsName", "productNoticeText", null)),
                "evidenceText", Map.of("type", List.of("string", "null"), "description",
                        "실제 원문 근거 요약. 일반 옵션명 추출은 null."),
                "calculation", Map.of("anyOf", List.of(calculation, Map.of("type", "null")))));
        return object(Map.of(
                "certain", Map.of("type", "boolean"),
                "confidence", Map.of("type", "number"),
                "mappings", Map.of("type", "array", "items", entry),
                "reason", Map.of("type", "string", "description",
                        "certain 값과 관계없이 반드시 작성하는 비어 있지 않은 한국어 판단 근거 요약. "
                        + "1~3문장, 공백과 줄바꿈을 포함하여 2000자 이내. 전체 매핑 목록을 반복하지 않는다.")));
    }
    private static Map<String,Object> object(Map<String,Object> properties) {
        return Map.of("type","object","properties",properties,"required",properties.keySet().stream().sorted().toList(),
                "additionalProperties",false);
    }
    private static InferenceException parseFailure() {
        return new InferenceException("AI_INVALID_JSON", HttpStatus.BAD_GATEWAY, "AI 응답을 정해진 JSON 형식으로 해석할 수 없습니다.");
    }
}
