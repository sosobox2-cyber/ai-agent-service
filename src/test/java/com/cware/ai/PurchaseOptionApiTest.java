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
import java.nio.file.Files;
import java.nio.file.Path;
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
    @Test void singleAllowedNameWorksInAiAndTestModes() throws Exception {
        ObjectNode body = mapper.valueToTree(Fixtures.ambiguous());
        body.putArray("allowedPurchaseOptions").add("색상");
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .9, java.util.List.of(
                new MappingProposal.Entry("1", "색상", "남색", .9)), "색상을 추출했습니다."));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.items[0].purchaseOptions.색상").value("남색"));
        mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errorCode").value("TEST_MODE"))
                .andExpect(jsonPath("$.validationErrors").isEmpty())
                .andExpect(jsonPath("$.items[0].purchaseOptions.색상").value("남색"));
        verify(ai, times(1)).infer(any());
    }
    @Test void emptyAllowedNamesStillFailBeforeAiInBothModes() throws Exception {
        ObjectNode body = mapper.valueToTree(Fixtures.ambiguous());
        body.putArray("allowedPurchaseOptions");
        for (String mode : new String[]{"false", "true"}) {
            mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(ai);
    }
    @Test void rejectedProposalExposesAiDataSeparatelyFromServerReason() throws Exception {
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .90, java.util.List.of(
                new MappingProposal.Entry("1", "색상", "남색", .79)), "원본에서 남색을 추출했습니다."));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Fixtures.ambiguous())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.optionMappings").isEmpty()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.aiAssessment.certain").value(true))
                .andExpect(jsonPath("$.aiAssessment.confidence").value(.90))
                .andExpect(jsonPath("$.aiAssessment.reason").value("원본에서 남색을 추출했습니다."))
                .andExpect(jsonPath("$.aiAssessment.mappings[0].value").value("남색"))
                .andExpect(jsonPath("$.aiAssessment.mappings[0].confidence").value(.79))
                .andExpect(jsonPath("$.serverAssessment.decisionCode").value("LOW_CONFIDENCE"))
                .andExpect(jsonPath("$.serverAssessment.confidenceThreshold").value(.80))
                .andExpect(jsonPath("$.serverAssessment.reason").value(org.hamcrest.Matchers.containsString("0.79")));
    }
    @Test void structuredProposalPreservesAiValueInItemsAndMappings() throws Exception {
        var request = mapper.readValue(Files.readString(Path.of("examples/capacity-request.json")), com.cware.ai.dto.InferenceRequest.class);
        var calculation = new com.cware.ai.dto.Calculation(com.cware.ai.dto.Calculation.Operation.DIRECT, "ml",
                java.util.List.of(new com.cware.ai.dto.Calculation.Operand("50", "ml",
                        new com.cware.ai.dto.Calculation.Evidence("goodsName", "50 ml"))), null);
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .90, java.util.List.of(
                new MappingProposal.Entry("1", "개당 용량", "50.0 ML", .90, "goodsName", "50 ml", calculation)), "개당 50ml입니다."));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.items[0].purchaseOptions['개당 용량']").value("50.0 ML"))
                .andExpect(jsonPath("$.aiAssessment.mappings[0].value").value("50.0 ML"))
                .andExpect(jsonPath("$.optionMappings[0].value").value("50.0 ML"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.operation").value("DIRECT"))
                .andExpect(jsonPath("$.serverAssessment.decisionCode").value("ACCEPTED"));
    }
    @Test void acceptsNoticeAndKeepsItsTextIntact() throws Exception {
        when(ai.infer(any())).thenReturn(Fixtures.proposal());
        String notice = "  제품 소재: 면 100%\n색상: 남색\n치수: 100, 150, 200  ";
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Fixtures.withNotice(Fixtures.request(), notice))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(ai).infer(argThat(request -> notice.equals(request.productNoticeText())));
    }
    @Test void noticeIsOptionalForExistingClients() throws Exception {
        when(ai.infer(any())).thenReturn(Fixtures.proposal());
        ObjectNode body = mapper.valueToTree(Fixtures.request());
        body.remove("productNoticeText");
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }
    @Test void noticeLengthLimitAppliesBeforeAiInBothModes() throws Exception {
        var request = Fixtures.withNotice(Fixtures.request(), "가".repeat(20001));
        for (String mode : new String[]{"false", "true"}) {
            mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(ai);
    }
    @Test void aignerExamplesExtractSlashSeparatedColorAndSizesInTestMode() throws Exception {
        for (String file : new String[]{"rule-request.json", "ai-request.json"}) {
            mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON)
                            .content(Files.readString(Path.of("examples", file))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.goodsId").value("68535109"))
                    .andExpect(jsonPath("$.errorCode").value("TEST_MODE"))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.autoApplyCandidate").value(false))
                    .andExpect(jsonPath("$.validationErrors").isEmpty())
                    .andExpect(jsonPath("$.items.length()").value(4))
                    .andExpect(jsonPath("$.optionMappings.length()").value(8))
                    .andExpect(jsonPath("$.items[0].purchaseOptions.색상").value("블랙"))
                    .andExpect(jsonPath("$.items[0].purchaseOptions['패션의류/잡화 사이즈']").value("90"))
                    .andExpect(jsonPath("$.items[1].purchaseOptions['패션의류/잡화 사이즈']").value("95"))
                    .andExpect(jsonPath("$.items[2].purchaseOptions['패션의류/잡화 사이즈']").value("100"))
                    .andExpect(jsonPath("$.items[3].purchaseOptions['패션의류/잡화 사이즈']").value("105"));
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
