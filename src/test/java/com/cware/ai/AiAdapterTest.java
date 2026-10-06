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
    }
    @Test void parsesNestedCalculationAndRejectsUnsupportedOperation() throws Exception {
        var calculation = new com.cware.ai.dto.Calculation(com.cware.ai.dto.Calculation.Operation.DIRECT, "ml",
                List.of(new com.cware.ai.dto.Calculation.Operand("50", "ml",
                        new com.cware.ai.dto.Calculation.Evidence("goodsName", "50ml"))), null);
        var proposal = new MappingProposal(true, .90, List.of(new MappingProposal.Entry("1", "개당 용량", "50ml", .90,
                "goodsName", "50ml", calculation)), "용량을 추출했습니다.");
        String json = new ObjectMapper().writeValueAsString(proposal);
        respond(json, "stop");
        assertThat(ai.infer(Fixtures.request())).isEqualTo(proposal);
        respond(json.replace("DIRECT", "RUN_CODE"), "stop");
        assertThatThrownBy(() -> ai.infer(Fixtures.request())).isInstanceOfSatisfying(InferenceException.class,
                e -> assertThat(e.code()).isEqualTo("AI_INVALID_JSON"));
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
        assertThatThrownBy(() -> ai.infer(Fixtures.request())).isInstanceOfSatisfying(InferenceException.class,
                e -> assertThat(e.code()).isEqualTo("AI_INVALID_JSON"));
    }
    @Test void rejectsTruncatedOutput() {
        respond("{}","length");
        assertThatThrownBy(() -> ai.infer(Fixtures.request())).isInstanceOfSatisfying(InferenceException.class,
                e -> assertThat(e.code()).isEqualTo("AI_INCOMPLETE_RESPONSE"));
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
