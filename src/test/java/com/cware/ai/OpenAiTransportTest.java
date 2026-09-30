package com.cware.ai;

import com.cware.ai.config.AiClientConfig;
import com.cware.ai.exception.InferenceException;
import com.cware.ai.inference.PurchaseOptionAiService;
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
