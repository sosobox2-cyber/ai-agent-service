package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** FULL 스키마 계약과 LIGHT의 null 전용 calculation 및 공통 치수 표현을 검증한다. */
class LightSchemaTest {
    final ObjectMapper mapper = new ObjectMapper();
    InferenceRequest fixture(String name) throws Exception {
        return mapper.readValue(Files.readString(Path.of("examples/" + name + "-request.json")), InferenceRequest.class);
    }

    @Test void fullSchemaPreservesPreviousContractAndLightChangesOnlyCalculation() throws Exception {
        var request = fixture("ai");
        var previousFull = mapper.readTree(Files.readString(Path.of("src/test/resources/schema/full-clothing-schema.json")));
        var full = mapper.valueToTree(PurchaseOptionAiService.schema(request));
        assertThat(full).isEqualTo(previousFull);
        var light = mapper.valueToTree(PurchaseOptionAiService.schema(request, PurchaseOptionPromptMode.LIGHT));
        var expected = previousFull.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.at("/properties/mappings/items/properties"))
                .set("calculation", mapper.readTree("{\"type\":\"null\"}"));
        assertThat(light).isEqualTo(expected);
        assertThat(light.at("/properties/mappings/items/required")).isEqualTo(full.at("/properties/mappings/items/required"));
        assertThat(full.at("/properties/mappings/items/properties/calculation/anyOf/0/properties/operation/enum"))
                .isEqualTo(mapper.valueToTree(List.of("DIRECT", "CONVERT", "SUM", "PACK_COUNT", "PACK_CONTENT", "PACK_CAPACITY")));
        for (var mode : PurchaseOptionPromptMode.values()) {
            var schema = PurchaseOptionAiService.schema(request, mode);
            assertOrdered(schema);
            // 모델은 properties 순서대로 출력하므로 판단을 마친 뒤 confidence와 certain을 쓰도록 고정한다.
            assertThat(keys(schema, "properties")).containsExactly("mappings", "reason", "confidence", "certain");
            assertThat(keys(at(schema, "properties", "mappings", "items"), "properties")).containsExactly(
                    "optionId", "targetPurchaseOptionName", "evidenceSource", "evidenceText", "calculation", "value", "confidence");
        }
        var calculation = at(PurchaseOptionAiService.schema(request), "properties", "mappings", "items", "properties", "calculation");
        var calculationObject = (Map<?,?>) ((List<?>) calculation.get("anyOf")).get(0);
        assertThat(keys(calculationObject, "properties")).containsExactly("operation", "context", "operands", "outputUnit");
        assertThat(keys(at(calculationObject, "properties", "operands", "items"), "properties")).containsExactly("evidence", "amount", "unit");
    }

    /** 스키마 키워드 키는 정렬되고 properties와 required는 같은 출력 순서를 가져야 한다. */
    void assertOrdered(Object value) {
        if (value instanceof Map<?,?> map) {
            assertThat(map.keySet().stream().map(Object::toString).toList()).isSorted();
            map.forEach((key, child) -> {
                if (!key.equals("properties")) assertOrdered(child);
                else {
                    var properties = (Map<?,?>) child;
                    assertThat(map.get("required")).isEqualTo(properties.keySet().stream().map(Object::toString).toList());
                    properties.values().forEach(this::assertOrdered);
                }
            });
        } else if (value instanceof List<?> list) list.forEach(this::assertOrdered);
    }

    static List<String> keys(Map<?,?> map, String key) {
        return ((Map<?,?>) map.get(key)).keySet().stream().map(Object::toString).toList();
    }

    static Map<?,?> at(Map<?,?> map, String... path) {
        Object current = map;
        for (String key : path) current = ((Map<?,?>) current).get(key);
        return (Map<?,?>) current;
    }

    @Test void lightKeepsCommonDimensionsAndEvidenceForEveryColor() throws Exception {
        var pillow = fixture("pillow");
        // Remove only quantity from the test input to exercise the existing LIGHT selector.
        var request = new InferenceRequest(pillow.goodsId(), pillow.goodsName(), pillow.categoryName(),
                List.of("색상", "사이즈"), pillow.options(), pillow.productNoticeText(), List.of(), pillow.productCompositionText());
        assertThat(new PurchaseOptionPromptSelector().select(request)).isEqualTo(PurchaseOptionPromptMode.LIGHT);
        var entries = new ArrayList<MappingProposal.Entry>();
        for (var option : request.options()) {
            entries.add(new MappingProposal.Entry(option.optionId(), "색상", option.optionName1(), .95));
            entries.add(new MappingProposal.Entry(option.optionId(), "사이즈", "43*25cm(+-3cm)", .95,
                    "productNoticeText", "치수:43*25cm(+-3cm)", null));
        }
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage(mapper.writeValueAsString(new MappingProposal(true, .95, entries, "공통 치수와 색상 추출"))),
                ChatGenerationMetadata.builder().finishReason("stop").build()))));
        var gateway = new PurchaseOptionAiService(model, mapper);
        var service = new PurchaseOptionInferenceService(new RequestValidator(), gateway, new ResultValidator(),
                new InputHashService(mapper), Fixtures.properties());
        var result = service.infer(request);
        assertThat(result.success()).isTrue();
        assertThat(result.items()).hasSize(2).allSatisfy(item ->
                assertThat(item.purchaseOptions()).containsEntry("사이즈", "43*25cm(+-3cm)"));
        assertThat(result.optionMappings().stream().filter(e -> e.targetPurchaseOptionName().equals("사이즈")))
                .hasSize(2).allSatisfy(e -> {
                    assertThat(e.evidenceSource()).isEqualTo("productNoticeText");
                    assertThat(e.evidenceText()).isEqualTo("치수:43*25cm(+-3cm)");
                    assertThat(e.calculation()).isNull();
                });
        var sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(sent.capture());
        String user = sent.getValue().getInstructions().get(1).getText();
        assertThat(mapper.readTree(user.substring(user.indexOf('{'))).at("/product/productNoticeText").asText())
                .isEqualTo(request.productNoticeText());
    }

    @Test void fullOnlyRulesRemainInFullAndSelectorIsUnchanged() throws Exception {
        var selector = new PurchaseOptionPromptSelector();
        for (String name : List.of("quantity", "weight", "seafood", "smartboard", "tv"))
            assertThat(selector.select(fixture(name))).isEqualTo(PurchaseOptionPromptMode.FULL);
        var provider = new PurchaseOptionPromptProvider("AUTO");
        assertThat(provider.system(PurchaseOptionPromptMode.LIGHT))
                .contains("43*25cm(+-3cm)", "evidenceSource=productNoticeText", "모든 단품")
                .doesNotContain("수산물 중량", "화면크기", "설치지원방식", "모델명", "스탠드", "75TR3DQ");
        assertThat(provider.system(PurchaseOptionPromptMode.FULL))
                .contains("수산물 중량=160g", "화면크기(in)", "방문설치", "75TR3DQ", "스탠드", "PACK_COUNT");
    }
}
