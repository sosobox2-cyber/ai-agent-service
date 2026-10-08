package com.cware.ai;

import com.cware.ai.inference.*;
import com.cware.ai.exception.InferenceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;
import java.net.SocketTimeoutException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class AiAdapterTest {
    ChatModel model;
    PurchaseOptionAiService ai;
    @BeforeEach void setUp() throws Exception {
        model=mock(ChatModel.class);
        ai=new PurchaseOptionAiService(model,new ObjectMapper());
    }
    void respond(String json,String finish) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage(json),ChatGenerationMetadata.builder().finishReason(finish).build()))));
    }
    @Test void parsesStructuredMapping() throws Exception {
        respond(new ObjectMapper().writeValueAsString(Fixtures.proposal()),"stop");
        assertThat(ai.infer(Fixtures.request())).isEqualTo(Fixtures.proposal());
        verify(model).call(any(Prompt.class));
    }
    @Test void unconfiguredScreenUnitsAreSentWithoutEnumAndPreservedInFinalResult() throws Exception {
        var request = new com.cware.ai.dto.InferenceRequest("50945478", "플럭스 109cm(43인치) TV",
                "플럭스", "가전", "112143", "TV", List.of("화면크기(cm)", "화면크기(in)"),
                List.of(new com.cware.ai.dto.SourceOption("1", "단품")), null);
        var entries = new java.util.ArrayList<MappingProposal.Entry>();
        for (String[] row : List.of(new String[]{"화면크기(cm)", "109", "cm"},
                new String[]{"화면크기(in)", "43", "인치"})) {
            var evidence = new com.cware.ai.dto.Calculation.Evidence("goodsName", "109cm(43인치)");
            entries.add(new MappingProposal.Entry("1", row[0], row[1] + row[2], .95,
                    evidence.source(), evidence.text(), new com.cware.ai.dto.Calculation(
                    com.cware.ai.dto.Calculation.Operation.DIRECT, row[2], List.of(
                    new com.cware.ai.dto.Calculation.Operand(row[1], row[2], evidence)), null)));
        }
        var proposal = new MappingProposal(true, .95, entries, "원문 화면크기 추출");
        when(model.call(any(Prompt.class))).thenReturn(response(proposal));
        var result = service().infer(request);
        assertThat(result.success()).isTrue();
        assertThat(result.items().get(0).purchaseOptions()).containsEntry("화면크기(cm)", "109cm")
                .containsEntry("화면크기(in)", "43인치");
        assertThat(result.optionMappings().get(1).calculation().outputUnit()).isEqualTo("인치");
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().get(0).getText())
                .contains("임의의 기본단위를 적용하지 않고", "109cm", "43인치");
        var unitSchema = new ObjectMapper().valueToTree(PurchaseOptionAiService.schema(request))
                .at("/properties/mappings/items/properties/calculation/anyOf/0/properties/outputUnit");
        assertThat(unitSchema.path("type").asText()).isEqualTo("string");
        assertThat(unitSchema.has("enum")).isFalse();
    }
    ChatResponse response(MappingProposal proposal) throws Exception {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(new ObjectMapper().writeValueAsString(proposal)),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
    }
    ChatResponse responseWithUsage(MappingProposal proposal) throws Exception {
        var nativeUsage = new org.springframework.ai.openai.api.OpenAiApi.Usage(200, 1000, 1200,
                new org.springframework.ai.openai.api.OpenAiApi.Usage.PromptTokensDetails(0, 800), null);
        return new ChatResponse(response(proposal).getResults(),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().model("gpt-4.1-mini")
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(1000, 200, 1200, nativeUsage)).build());
    }
    @Test void exposesBothAttemptsWithoutConsoleLoggingAndDoesNotLeakAcrossRequests() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(responseWithUsage(partial(true)),
                responseWithUsage(Fixtures.proposal()), responseWithUsage(Fixtures.proposal()));
        var service = service();
        var first = service.infer(Fixtures.request());
        assertThat(first.success()).isTrue();
        assertThat(first.aiUsage()).hasSize(2);
        assertThat(first.aiUsage()).extracting(com.cware.ai.dto.AiCallUsage::attempt).containsExactly(1, 2);
        assertThat(first.aiUsage().get(0).input_tokens()).isEqualTo(1000);
        assertThat(first.aiUsage().get(1).cached_tokens()).isEqualTo(800);
        assertThat(first.aiUsage().get(1).estimated_cost_usd()).isEqualByComparingTo("0.00048");
        var second = service.infer(Fixtures.request());
        assertThat(second.aiUsage()).hasSize(1);
        assertThat(second.aiUsage().get(0).attempt()).isEqualTo(1);
        assertThat(first.aiUsage().get(0).inferenceId()).isEqualTo(first.aiUsage().get(1).inferenceId());
        assertThat(second.aiUsage().get(0).inferenceId()).isNotEqualTo(first.aiUsage().get(0).inferenceId());
        assertThat(first.aiUsage()).hasSize(2);
    }
    @Test void validationFailureStillReturnsPaidCallUsage() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(responseWithUsage(partial(true)));
        var result = service().infer(Fixtures.request());
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.aiUsage()).hasSize(2);
        assertThat(result.items()).isEmpty();
    }
    MappingProposal partial(boolean certain) {
        return new MappingProposal(certain, .95, Fixtures.proposal().mappings().subList(0, 3), "첫 단품 판단");
    }
    com.cware.ai.service.PurchaseOptionInferenceService service() {
        return new com.cware.ai.service.PurchaseOptionInferenceService(new RequestValidator(), ai,
                new ResultValidator(), new com.cware.ai.util.InputHashService(new ObjectMapper()), Fixtures.properties());
    }
    @Test void failsOverMissingIdsToFullWithSameInputAndAcceptsReplacement() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response(partial(true)), response(Fixtures.proposal()));
        var result = service().infer(Fixtures.request());
        assertThat(result.success()).isTrue();
        assertThat(result.items()).hasSize(3);
        assertThat(result.optionMappings()).hasSize(9);
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        var first = prompts.getAllValues().get(0).getInstructions();
        var correction = prompts.getAllValues().get(1).getInstructions();
        assertThat(first).hasSize(2);
        assertThat(correction).hasSize(2);
        assertThat(correction.get(0).getText()).isEqualTo(new PurchaseOptionPromptProvider("AUTO").system(PurchaseOptionPromptMode.FULL));
        assertThat(correction.get(1).getText()).isEqualTo(first.get(1).getText());
    }
    @Test void repeatedOmissionFailsValidationAfterExactlyOneCorrection() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response(partial(true)));
        var result = service().infer(Fixtures.request());
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.validationErrors()).contains("단품의 구매옵션 매핑이 누락되었습니다.");
        assertThat(result.items()).isEmpty();
        assertThat(result.autoApplyCandidate()).isFalse();
        verify(model, times(2)).call(any(Prompt.class));
    }
    @Test void uncertainLightResponseFailsOverOnce() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response(partial(false)));
        var result = service().infer(Fixtures.request());
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.items()).isEmpty();
        verify(model, times(2)).call(any(Prompt.class));
    }
    @Test void correctionStillChecksAllowedNamesAndConfidence() throws Exception {
        var entries = new java.util.ArrayList<>(Fixtures.proposal().mappings());
        entries.set(0, new MappingProposal.Entry("1", "허용되지 않은 옵션", "값", .99));
        var invalid = new MappingProposal(true, .99, entries, "보정 결과");
        when(model.call(any(Prompt.class))).thenReturn(response(partial(true)), response(invalid));
        var result = service().infer(Fixtures.request());
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.items()).isEmpty();
        verify(model, times(2)).call(any(Prompt.class));

        reset(model);
        var low = new MappingProposal(true, .5, Fixtures.proposal().mappings(), "신뢰도 낮음");
        when(model.call(any(Prompt.class))).thenReturn(response(partial(true)), response(low));
        result = service().infer(Fixtures.request());
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.items()).isEmpty();
        verify(model, times(2)).call(any(Prompt.class));
    }
    @Test void uncertainCorrectionIsNotApplied() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response(partial(true)), response(partial(false)));
        var result = service().infer(Fixtures.request());
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.aiAssessment().certain()).isFalse();
        assertThat(result.items()).isEmpty();
        verify(model, times(2)).call(any(Prompt.class));
    }
    @Test void parsesNestedCalculationAndRejectsUnsupportedOperation() throws Exception {
        var calculation = new com.cware.ai.dto.Calculation(com.cware.ai.dto.Calculation.Operation.DIRECT, "ml",
                List.of(new com.cware.ai.dto.Calculation.Operand("50", "ml",
                        new com.cware.ai.dto.Calculation.Evidence("goodsName", "50ml"))), null);
        var proposal = new MappingProposal(true, .90, List.of(new MappingProposal.Entry("1", "개당 용량", "50ml", .90,
                "goodsName", "50ml", calculation)), "용량을 추출했습니다.");
        String json = new ObjectMapper().writeValueAsString(proposal);
        var request = new com.cware.ai.dto.InferenceRequest("1", "50ml", "브랜드", "화장품", "1", "화장품",
                List.of("개당 용량"), List.of(new com.cware.ai.dto.SourceOption("1", "50ml")), null);
        respond(json, "stop");
        assertThat(ai.infer(request)).isEqualTo(proposal);
        respond(json.replace("DIRECT", "RUN_CODE"), "stop");
        assertThat(ai.infer(request).reason()).contains("INVALID_RESPONSE");
    }
    @Test void sendsNoticeAsProductDataWithContextRules() throws Exception {
        respond(new ObjectMapper().writeValueAsString(Fixtures.proposal()), "stop");
        String notice = "제품 소재: 면 100%\n색상: 남색\n치수: 100, 150, 200";
        ai.infer(Fixtures.withNotice(Fixtures.request(), notice));
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captured.capture());
        String input = captured.getValue().getInstructions().get(1).getText();
        var product = new ObjectMapper().readTree(input.substring(input.indexOf('{'))).path("product");
        assertThat(product.path("productNoticeText").asText()).isEqualTo(notice);
        assertThat(captured.getValue().getInstructions().get(0).getText())
                .contains("원본 옵션명에 없는 값을 정보고시에서 가져와 채우지 않는다");
    }
    @Test void sendsCallerUnitSettingsAndDefaultRulesToModel() throws Exception {
        var mapper = new ObjectMapper();
        var request = mapper.readValue(java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/unit-request.json")),
                com.cware.ai.dto.InferenceRequest.class);
        respond(mapper.writeValueAsString(Fixtures.proposal()), "stop");
        ai.infer(request);
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captured.capture());
        String input = captured.getValue().getInstructions().get(1).getText();
        var settings = mapper.readTree(input.substring(input.indexOf('{'))).at("/product/purchaseOptionUnits/0");
        assertThat(settings.path("purchaseOptionName").asText()).isEqualTo("수량");
        assertThat(settings.path("defaultUnit").asText()).isEqualTo("개");
        assertThat(settings.path("unitOptions").toString()).isEqualTo("[\"개\",\"박스\",\"세트\"]");
        assertThat(captured.getValue().getInstructions().get(0).getText())
                .contains("설정된 옵션에는 아래 단위 규칙을 기존 출력 단위 규칙과 예시보다 우선 적용한다", "certain=false");
    }
    @ParameterizedTest @ValueSource(strings={
        "not json","null","{} {}",
        "{\"certain\":true,\"confidence\":0.99,\"mappings\":[],\"reason\":\"ok\",\"items\":[]}",
        "{\"certain\":true,\"certain\":false}",
        "{\"certain\":\"true\",\"confidence\":\"0.99\",\"mappings\":[],\"reason\":\"ok\"}"})
    void rejectsMalformedOrExtendedJson(String json) {
        respond(json,"stop");
        assertThat(ai.infer(Fixtures.request()).reason()).contains("REVIEW_REQUIRED", "INVALID_RESPONSE");
        verify(model, times(2)).call(any(Prompt.class));
    }
    @Test void rejectsTruncatedOutput() {
        respond("{}","length");
        assertThat(ai.infer(Fixtures.request()).reason()).contains("REVIEW_REQUIRED", "INVALID_RESPONSE");
        verify(model, times(2)).call(any(Prompt.class));
    }
    @Test void timeoutIsSanitized() {
        when(model.call(any(Prompt.class))).thenThrow(new ResourceAccessException("private upstream text",new SocketTimeoutException()));
        assertThatThrownBy(() -> ai.infer(Fixtures.request())).isInstanceOfSatisfying(InferenceException.class,
                e -> { assertThat(e.code()).isEqualTo("AI_TIMEOUT"); assertThat(e.getMessage()).doesNotContain("private"); });
    }
    @Test void schemaOnlyAllowsRequestedNames() throws Exception {
        var tree=new ObjectMapper().valueToTree(PurchaseOptionAiService.schema(Fixtures.request()));
        assertThat(tree.at("/properties/mappings/items/properties/targetPurchaseOptionName/enum").toString())
                .isEqualTo("[\"핏\",\"색상\",\"사이즈\"]");
        assertThat(tree.at("/properties/mappings/items/properties/optionId/enum").toString())
                .isEqualTo("[\"1\",\"2\",\"3\"]");
        assertThat(tree.path("additionalProperties").asBoolean()).isFalse();
        assertThat(tree.at("/properties/mappings/items/properties/calculation/anyOf/0/properties/operation/enum").toString())
                .contains("DIRECT", "CONVERT", "SUM", "PACK_COUNT", "PACK_CONTENT");
        assertThat(tree.at("/properties/mappings/items/properties/calculation/anyOf/0/additionalProperties").asBoolean()).isFalse();
    }
}
