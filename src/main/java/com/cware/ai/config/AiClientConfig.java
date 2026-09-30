package com.cware.ai.config;

import com.cware.ai.exception.InferenceException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.*;
import java.io.IOException;
import java.net.URI;

@Configuration
public class AiClientConfig {
    @Bean
    public ChatModel openAiChatModel(InferenceProperties properties,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model}") String model,
            @Value("${spring.ai.openai.chat.options.temperature:0}") double temperature,
            @Value("${spring.ai.openai.chat.options.max-tokens:4096}") int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            return prompt -> { throw new InferenceException("AI_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 추론을 사용하려면 OPENAI_API_KEY를 설정해야 합니다."); };
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        OpenAiApi api = OpenAiApi.builder().apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .responseErrorHandler(new SafeErrorHandler()).build();
        // 비용 및 지연을 예측할 수 있도록 1단계에서는 자동 재시도를 하지 않는다.
        return OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(temperature).maxTokens(maxTokens).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }

    public static class SafeErrorHandler implements ResponseErrorHandler {
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException { return response.getStatusCode().isError(); }
        @Override
        public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
            // 응답 본문과 헤더를 읽거나 오류 메시지에 포함하지 않는다.
            int status = response.getStatusCode().value();
            if (status == 429) throw new InferenceException("AI_RATE_LIMIT", HttpStatus.TOO_MANY_REQUESTS, "AI 호출 한도를 초과했습니다.");
            if (status == 408 || status == 504)
                throw new InferenceException("AI_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, "AI 응답 시간이 초과되었습니다.");
            throw new InferenceException("AI_UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, "AI 서비스 호출에 실패했습니다.");
        }
    }
}
