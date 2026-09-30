package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
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
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class PurchaseOptionAiService implements OptionInferenceGateway {
    private final ChatModel model;
    private final ObjectMapper mapper;
    private final String systemPrompt;
    private final String userPrompt;

    public PurchaseOptionAiService(ChatModel model, ObjectMapper mapper) throws IOException {
        this.model = model;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.setConfig(this.mapper.getDeserializationConfig().without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        systemPrompt = new ClassPathResource("prompts/coupang-purchase-option-system.txt").getContentAsString(StandardCharsets.UTF_8);
        userPrompt = new ClassPathResource("prompts/coupang-purchase-option-user.txt").getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public MappingProposal infer(InferenceRequest request) {
        try {
            String input = userPrompt + "\n" + mapper.writeValueAsString(Map.of("product", request));
            ResponseFormat format = ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(ResponseFormat.JsonSchema.builder().name("purchase_option_mapping")
                            .strict(true).schema(schema(request)).build()).build();
            ChatResponse response = model.call(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(input)),
                    OpenAiChatOptions.builder().responseFormat(format).build()));
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

    /** 매 요청의 허용 이름을 JSON Schema enum에도 주입하며 서버 검증을 별도로 수행한다. */
    public static Map<String,Object> schema(InferenceRequest request) {
        Map<String,Object> entry = object(Map.of(
                "optionId", Map.of("type", "string", "enum", request.options().stream()
                        .map(option -> option.optionId()).toList()),
                "targetPurchaseOptionName", Map.of("type", "string", "enum", request.allowedPurchaseOptions()),
                "value", Map.of("type", "string"),
                "confidence", Map.of("type", "number")));
        return object(Map.of(
                "certain", Map.of("type", "boolean"),
                "confidence", Map.of("type", "number"),
                "mappings", Map.of("type", "array", "items", entry),
                "reason", Map.of("type", "string")));
    }
    private static Map<String,Object> object(Map<String,Object> properties) {
        return Map.of("type","object","properties",properties,"required",properties.keySet().stream().sorted().toList(),
                "additionalProperties",false);
    }
    private static InferenceException parseFailure() {
        return new InferenceException("AI_INVALID_JSON", HttpStatus.BAD_GATEWAY, "AI 응답을 정해진 JSON 형식으로 해석할 수 없습니다.");
    }
}
