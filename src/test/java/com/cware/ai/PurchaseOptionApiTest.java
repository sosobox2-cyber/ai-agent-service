package com.cware.ai;

import com.cware.ai.exception.InferenceException;
import com.cware.ai.inference.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.ai.openai.api-key=test-placeholder")
@AutoConfigureMockMvc
class PurchaseOptionApiTest {
    private static final String URL="/api/v1/coupang/purchase-options/infer";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean PurchaseOptionAiService ai;
    @Test void extractedValuesRunThroughFullContext() throws Exception {
        when(ai.infer(any())).thenReturn(Fixtures.proposal());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Fixtures.request())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].purchaseOptions.색상").value("남색"))
                .andExpect(jsonPath("$.items[0].purchaseOptions.사이즈").value("100"))
                .andExpect(jsonPath("$.inputHash").isNotEmpty());
        verify(ai).infer(any());
    }
    @Test void malformedAndInvalidRequestsFailBeforeAi() throws Exception {
        for (String body:new String[]{"{","{}","{\"unexpected\":1}","null"}) {
            mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        }
        verifyNoInteractions(ai);
    }
    @Test void oldOptionValueFieldsAreRejected() throws Exception {
        ObjectNode body = mapper.valueToTree(Fixtures.request());
        ((ObjectNode) body.path("options").get(0)).put("optionValue1", "100");
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_JSON"));
        verifyNoInteractions(ai);
    }
    @Test void invalidTestModeParameterIsBadRequest() throws Exception {
        mvc.perform(post(URL).param("testMode", "unknown").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Fixtures.request())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }
    @Test void unsafeMappingIsBusinessFailure() throws Exception {
        when(ai.infer(any())).thenReturn(Fixtures.proposal());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Fixtures.ambiguous())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.validationErrors").isNotEmpty());
    }
    @Test void providerErrorsUseCorrectHttpStatus() throws Exception {
        when(ai.infer(any())).thenThrow(new InferenceException("AI_RATE_LIMIT",HttpStatus.TOO_MANY_REQUESTS,"AI 호출 한도를 초과했습니다."));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Fixtures.ambiguous())))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.errorCode").value("AI_RATE_LIMIT"));
    }
}
