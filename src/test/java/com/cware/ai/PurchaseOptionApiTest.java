package com.cware.ai;

import com.cware.ai.exception.InferenceException;
import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.ai.openai.api-key=test-placeholder")
@AutoConfigureMockMvc
class PurchaseOptionApiTest {
    @Test void kitchenTowelUsesGoodsNameForSheetAndRollCountsInTestMode() throws Exception {
        mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/kitchen-towel-request.json"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("TEST_MODE"))
                .andExpect(jsonPath("$.validationErrors").isEmpty())
                .andExpect(jsonPath("$.items[0].purchaseOptions['개당 수량']").value("140매"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['수량']").value("12개"))
                .andExpect(jsonPath("$.optionMappings[0].evidenceSource").value("goodsName"))
                .andExpect(jsonPath("$.optionMappings[1].calculation.operands[0].unit").value("롤"))
                .andExpect(jsonPath("$.aiUsage").isEmpty());
        verifyNoInteractions(ai);
    }
    @Test void creamExampleReturnsMainProductWeightAndCountWithoutFreeTrialInTestMode() throws Exception {
        mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/cream-request.json"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("TEST_MODE"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.autoApplyCandidate").value(false))
                .andExpect(jsonPath("$.validationErrors").isEmpty())
                .andExpect(jsonPath("$.items[0].purchaseOptions['개당 중량']").value("120g"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['수량']").value("3개"))
                .andExpect(jsonPath("$.optionMappings[0].evidenceSource").value("productCompositionText"))
                .andExpect(jsonPath("$.optionMappings[1].calculation.operands[0].unit").value("통"))
                .andExpect(jsonPath("$.aiUsage").isEmpty());
        verifyNoInteractions(ai);
    }
    @Test void toiletPaperExampleReturnsPackContentsLengthAndSalesQuantityInTestMode() throws Exception {
        mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/toilet-paper-request.json"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("TEST_MODE"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.autoApplyCandidate").value(false))
                .andExpect(jsonPath("$.validationErrors").isEmpty())
                .andExpect(jsonPath("$.items[0].purchaseOptions['개당 수량']").value("30롤"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['길이']").value("22m"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['수량']").value("4개"))
                .andExpect(jsonPath("$.optionMappings[2].calculation.operation").value("PACK_COUNT"))
                .andExpect(jsonPath("$.optionMappings[2].calculation.operands[0].unit").value("팩"));
        verifyNoInteractions(ai);
    }
    @Test void missingLengthAndColorWithoutDefaultUnitsReturnNone() throws Exception {
        var request = new InferenceRequest("length-product", "구성품 10개", null,
                java.util.List.of("길이", "수량", "색상"), java.util.List.of(new SourceOption("1", "10개")),
                "구성품 10개");
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .99,
                java.util.List.of(new MappingProposal.Entry("1", "수량", "10개", .99)), "수량만 추출했습니다."));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.items[0].purchaseOptions.길이").value("없음"))
                .andExpect(jsonPath("$.items[0].purchaseOptions.수량").value("10개"))
                .andExpect(jsonPath("$.items[0].purchaseOptions.색상").value("없음"))
                .andExpect(jsonPath("$.optionMappings.length()").value(1))
                .andExpect(jsonPath("$.aiAssessment.mappings.length()").value(1));
    }
    private static final String URL="/api/v1/coupang/purchase-options/infer";
    @Test void emptyOptionsDefaultToSingleProductInTestMode() throws Exception {
        var original = (ObjectNode) mapper.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("examples/kitchen-towel-request.json")));
        for (int variant = 0; variant < 4; variant++) {
            var body = original.deepCopy();
            if (variant == 0) body.remove("options");
            if (variant == 1) body.putNull("options");
            if (variant == 2) body.putArray("options");
            if (variant == 3) body.putArray("options").addObject().put("optionId", "1").put("optionName1", " ");
            mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].optionId").value("1"))
                    .andExpect(jsonPath("$.items[0].purchaseOptions['개당 수량']").value("140매"))
                    .andExpect(jsonPath("$.items[0].purchaseOptions['수량']").value("12개"));
        }
        verifyNoInteractions(ai);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired com.cware.ai.config.AiUsagePricing usagePricing;
    @Test void usagePricesAreBoundFromExternalConfiguration() {
        assertThat(usagePricing.estimate("gpt-4.1-mini", 1000, 800, 200)).isEqualByComparingTo("0.00048");
        assertThat(usagePricing.estimate("gpt-4.1-mini-2025-04-14", 1000, 800, 200)).isEqualByComparingTo("0.00048");
        assertThat(usagePricing.estimate("unconfigured-model", 1000, 0, 200)).isNull();
    }
    @MockitoBean PurchaseOptionAiService ai;
    @BeforeEach void delegateUsageOverloadToExistingMockProposals() {
        when(ai.infer(any(), any())).thenAnswer(invocation -> ai.infer(invocation.getArgument(0)));
    }
    @Test void patchWithSavedUnitsReturnsPackCountAndContentsInTestMode() throws Exception {
        mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/quantity-request.json"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errorCode").value("TEST_MODE"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.autoApplyCandidate").value(false))
                .andExpect(jsonPath("$.validationErrors").isEmpty())
                .andExpect(jsonPath("$.items[0].purchaseOptions.수량").value("9개"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['개당 수량']").value("50개입"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.operation").value("PACK_CONTENT"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.outputUnit").value("개입"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.operands[0].amount").value("50"))
                .andExpect(jsonPath("$.optionMappings[1].calculation.operation").value("DIRECT"))
                .andExpect(jsonPath("$.optionMappings[1].calculation.outputUnit").value("개"))
                .andExpect(jsonPath("$.optionMappings[1].calculation.operands[0].amount").value("9"))
                .andExpect(jsonPath("$.optionMappings[1].calculation.operands[0].unit").value("박스"));
        verifyNoInteractions(ai);
    }
    @Test void sunscreenWithSavedUnitsReturnsCountAndPerItemCapacityInTestMode() throws Exception {
        mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/capacity-request.json"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errorCode").value("TEST_MODE"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.autoApplyCandidate").value(false))
                .andExpect(jsonPath("$.validationErrors").isEmpty())
                .andExpect(jsonPath("$.items[0].purchaseOptions.수량").value("7개"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['개당 용량']").value("50ml"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.operation").value("DIRECT"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.outputUnit").value("ml"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.operands[0].amount").value("50"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.context").doesNotExist())
                .andExpect(jsonPath("$.optionMappings[1].calculation.operation").value("DIRECT"))
                .andExpect(jsonPath("$.optionMappings[1].calculation.outputUnit").value("개"))
                .andExpect(jsonPath("$.optionMappings[1].calculation.operands[0].amount").value("7"));
        verifyNoInteractions(ai);
    }
    @Test void kimchiWithSavedUnitsReturnsCombinedWeightInTestMode() throws Exception {
        mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/weight-request.json"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errorCode").value("TEST_MODE"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.autoApplyCandidate").value(false))
                .andExpect(jsonPath("$.validationErrors").isEmpty())
                .andExpect(jsonPath("$.items[0].purchaseOptions.수량").value("1세트"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['개당 중량']").value("5.2kg"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.operation").value("SUM"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.outputUnit").value("kg"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.operands[0].amount").value("4.2"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.operands[1].amount").value("1"))
                .andExpect(jsonPath("$.optionMappings[1].calculation.operation").value("PACK_COUNT"));
        verifyNoInteractions(ai);
    }
    @Test void toothbrushWithSavedUnitsReturnsSetAndContentsInTestMode() throws Exception {
        mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/set-request.json"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errorCode").value("TEST_MODE"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.autoApplyCandidate").value(false))
                .andExpect(jsonPath("$.validationErrors").isEmpty())
                .andExpect(jsonPath("$.items[0].purchaseOptions.수량").value("1세트"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['개당 수량']").value("10매입"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.operation").value("PACK_CONTENT"))
                .andExpect(jsonPath("$.optionMappings[0].calculation.outputUnit").value("매입"))
                .andExpect(jsonPath("$.optionMappings[1].calculation.operation").value("PACK_COUNT"))
                .andExpect(jsonPath("$.optionMappings[1].calculation.outputUnit").value("세트"));
        verifyNoInteractions(ai);
    }
    @Test void callerSuppliedUnitsReachAiAndTestMode() throws Exception {
        String body = Files.readString(Path.of("src/test/resources/unit-request.json"));
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .9, java.util.List.of(
                new MappingProposal.Entry("1", "수량", "6박스", .9),
                new MappingProposal.Entry("2", "수량", "6세트", .9),
                new MappingProposal.Entry("3", "수량", "6개", .9)), "원문 단위와 기본단위를 적용했습니다."));
        for (String mode : new String[]{"false", "true"}) {
            mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.validationErrors").isEmpty())
                    .andExpect(jsonPath("$.items[0].purchaseOptions.수량").value("6박스"))
                    .andExpect(jsonPath("$.items[1].purchaseOptions.수량").value("6세트"))
                    .andExpect(jsonPath("$.items[2].purchaseOptions.수량").value("6개"));
        }
        verify(ai).infer(argThat(r -> r.purchaseOptionUnits().size() == 1
                && r.purchaseOptionUnits().get(0).defaultUnit().equals("개")
                && r.purchaseOptionUnits().get(0).unitOptions().equals(java.util.List.of("개", "박스", "세트"))));
    }

    @Test void invalidUnitSettingsFailBeforeAi() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(Files.readString(Path.of("src/test/resources/unit-request.json")));
        ((ObjectNode) body.withArray("purchaseOptionUnits").get(0)).put("defaultUnit", " ");
        for (String mode : new String[]{"false", "true"})
            mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(ai);
    }
    @Test void defaultOutsideChoicesWorksInAiAndTestModes() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(Files.readString(Path.of("src/test/resources/unit-request.json")));
        ((ObjectNode) body.withArray("purchaseOptionUnits").get(0)).putArray("unitOptions").add("박스").add("세트");
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .9, java.util.List.of(
                new MappingProposal.Entry("1", "수량", "6박스", .9),
                new MappingProposal.Entry("2", "수량", "6세트", .9),
                new MappingProposal.Entry("3", "수량", "6개", .9)), "기본단위를 적용했습니다."));
        for (String mode : new String[]{"false", "true"})
            mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.validationErrors").isEmpty())
                    .andExpect(jsonPath("$.items[2].purchaseOptions.수량").value("6개"));
    }
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
        ObjectNode input = (ObjectNode) mapper.readTree(Files.readString(Path.of("examples/capacity-request.json")));
        // 단위 설정이 없는 기존 요청은 AI의 원래 값 표기를 그대로 보존한다.
        input.remove("purchaseOptionUnits");
        var request = mapper.treeToValue(input, com.cware.ai.dto.InferenceRequest.class);
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
    @Test void missingNullEmptyAndBlankNoticeFailBeforeAiInBothModes() throws Exception {
        for (String mode : new String[]{"false", "true"}) {
            for (String notice : new String[]{null, "", " \t\r\n "}) {
                ObjectNode body = mapper.valueToTree(Fixtures.request());
                body.put("productNoticeText", notice);
                mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(body)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
            }
            ObjectNode body = mapper.valueToTree(Fixtures.request());
            body.remove("productNoticeText");
            mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(ai);
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
    @Test void tvExamplePreservesScreenUnitsWithoutUnitSettingsInTestMode() throws Exception {
        String body = java.nio.file.Files.readString(java.nio.file.Path.of("examples/tv-request.json"));
        assertThat(mapper.readTree(body).has("purchaseOptionUnits")).isFalse();
        mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("TEST_MODE"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.autoApplyCandidate").value(false))
                .andExpect(jsonPath("$.validationErrors").isEmpty())
                .andExpect(jsonPath("$.items[0].purchaseOptions['화면크기(cm)']").value("109cm"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['화면크기(in)']").value("43인치"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['화면크기 (cm/(인치))']").value("109cm(43인치)"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['설치지원방식']").value("자가설치"))
                .andExpect(jsonPath("$.items[0].purchaseOptions['스탠드/벽걸이 구분']").value("없음"));
        verifyNoInteractions(ai);
    }

    @Test void returnsUsageAsAdditionalMetadataWithoutChangingInferenceResult() throws Exception {
        doAnswer(invocation -> {
            java.util.function.Consumer<com.cware.ai.dto.AiCallUsage> usage = invocation.getArgument(1);
            usage.accept(new com.cware.ai.dto.AiCallUsage("gpt-4.1-mini", PurchaseOptionPromptMode.LIGHT,
                    1, 1000, 800, 200, 1200, new java.math.BigDecimal("0.00048")));
            return Fixtures.ambiguousProposal();
        }).when(ai).infer(any(), any());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Fixtures.ambiguous())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.items[0].purchaseOptions.색상").value("남색"))
                .andExpect(jsonPath("$.aiUsage.length()").value(1))
                .andExpect(jsonPath("$.aiUsage[0].mode").value("LIGHT"))
                .andExpect(jsonPath("$.aiUsage[0].input_tokens").value(1000))
                .andExpect(jsonPath("$.aiUsage[0].cached_tokens").value(800))
                .andExpect(jsonPath("$.aiUsage[0].output_tokens").value(200))
                .andExpect(jsonPath("$.aiUsage[0].total_tokens").value(1200));
    }

    @Test void optionalInternalCategoryIsAcceptedInBothModes() throws Exception {
        when(ai.infer(any())).thenReturn(Fixtures.proposal());
        for (String mode : new String[]{"false", "true"}) {
            for (String category : new String[]{null, "", " \t\r\n "}) {
                ObjectNode body = mapper.valueToTree(Fixtures.request());
                body.put("categoryName", category);
                mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(body)))
                        .andExpect(status().isOk());
            }
            ObjectNode body = mapper.valueToTree(Fixtures.request());
            body.remove("categoryName");
            mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }
    }

    @Test void oversizedInternalCategoryFailsBeforeAiInBothModes() throws Exception {
        for (String mode : new String[]{"false", "true"}) {
            ObjectNode body = mapper.valueToTree(Fixtures.request());
            body.put("categoryName", "가".repeat(501));
            mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(ai);
    }

    @Test void categoryMaximumLengthIsAcceptedAndRetainedForLogging() throws Exception {
        when(ai.infer(any())).thenReturn(Fixtures.proposal());
        ObjectNode body = mapper.valueToTree(Fixtures.request());
        String category = "가".repeat(500);
        body.put("categoryName", category);
        for (String mode : new String[]{"false", "true"}) {
            mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }
        verify(ai).infer(argThat(request -> category.equals(request.categoryName())));
    }

    @Test void removedBrandFieldIsRejected() throws Exception {
        ObjectNode body = mapper.valueToTree(Fixtures.request());
        body.put("brand", "예전 브랜드");
        for (String mode : new String[]{"false", "true"}) {
            mvc.perform(post(URL).param("testMode", mode).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_JSON"));
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
