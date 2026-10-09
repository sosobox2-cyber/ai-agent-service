package com.cware.ai;

import com.cware.ai.config.AiClientConfig;
import com.cware.ai.exception.InferenceException;
import com.cware.ai.inference.PurchaseOptionAiService;
import com.cware.ai.inference.AiUsageLogger;
import com.cware.ai.inference.PurchaseOptionPromptMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.*;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiTransportTest {
    @Test void realTransportPreservesNativeCachedTokenUsage() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var mapper = new ObjectMapper();
        String body = mapper.writeValueAsString(java.util.Map.of(
                "id", "test", "object", "chat.completion", "created", 1, "model", "gpt-4.1-mini",
                "choices", java.util.List.of(java.util.Map.of("index", 0, "finish_reason", "stop", "message",
                        java.util.Map.of("role", "assistant", "content", mapper.writeValueAsString(Fixtures.proposal())))),
                "usage", java.util.Map.of("prompt_tokens", 1000, "completion_tokens", 200, "total_tokens", 1200,
                        "prompt_tokens_details", java.util.Map.of("cached_tokens", 800))));
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var response = model(builder).call(new org.springframework.ai.chat.prompt.Prompt("test"));
        var usage = AiUsageLogger.measure(PurchaseOptionPromptMode.LIGHT, 1, response);
        assertThat(usage.input_tokens()).isEqualTo(1000);
        assertThat(usage.cached_tokens()).isEqualTo(800);
        assertThat(usage.output_tokens()).isEqualTo(200);
        assertThat(usage.total_tokens()).isEqualTo(1200);
        assertThat(usage.estimated_cost_usd()).isNull(); // 가격 설정 없는 순수 usage 측정
        server.verify();
    }
    @Test void actualSpringAiSerializesStrictSchemaAndParsesResponse() throws Exception {
        RestClient.Builder builder=RestClient.builder();
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        var model=model(builder);
        String json=new ObjectMapper().writeValueAsString(Fixtures.proposal());
        String body=new ObjectMapper().writeValueAsString(java.util.Map.of(
            "id","test-completion","object","chat.completion","created",1,"model","test-model",
            "choices",java.util.List.of(java.util.Map.of("index",0,"finish_reason","stop",
            "message",java.util.Map.of("role","assistant","content",json)))));
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.mappings.items.properties.targetPurchaseOptionName.enum[0]").value("핏"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.mappings.items.properties.value.type").value("string"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.mappings.items.properties.calculation.type").value("null"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.mappings.items.properties.calculation.anyOf").doesNotExist())
                .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        var ai=new PurchaseOptionAiService(model,new ObjectMapper());
        assertThat(ai.infer(Fixtures.request())).isEqualTo(Fixtures.proposal());
        server.verify();
    }
    @Test void statusErrorsHaveSafeCodesAndAreNotRetried() throws Exception {
        for (HttpStatus status: new HttpStatus[]{HttpStatus.TOO_MANY_REQUESTS,HttpStatus.INTERNAL_SERVER_ERROR,HttpStatus.GATEWAY_TIMEOUT}) {
            RestClient.Builder builder=RestClient.builder();
            MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                    .andRespond(withStatus(status).body("sensitive-upstream-body"));
            var ai=new PurchaseOptionAiService(model(builder),new ObjectMapper());
            String expected=status==HttpStatus.TOO_MANY_REQUESTS ? "AI_RATE_LIMIT"
                    : status==HttpStatus.GATEWAY_TIMEOUT ? "AI_TIMEOUT" : "AI_UPSTREAM_ERROR";
            assertThatThrownBy(() -> ai.infer(Fixtures.request())).isInstanceOfSatisfying(InferenceException.class,
                    e -> { assertThat(e.code()).isEqualTo(expected); assertThat(e.getMessage()).doesNotContain("sensitive"); });
            server.verify();
        }
    }
    private OpenAiChatModel model(RestClient.Builder builder) {
        return OpenAiChatModel.builder().openAiApi(OpenAiApi.builder().apiKey("test-placeholder")
                .restClientBuilder(builder).responseErrorHandler(new AiClientConfig.SafeErrorHandler()).build())
                .defaultOptions(OpenAiChatOptions.builder().model("test-model").build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }
}
