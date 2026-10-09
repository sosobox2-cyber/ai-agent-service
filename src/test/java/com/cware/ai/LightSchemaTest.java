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
                .isEqualTo(mapper.valueToTree(List.of("DIRECT", "CONVERT", "SUM", "PACK_COUNT", "PACK_CONTENT")));
        assertOrdered(PurchaseOptionAiService.schema(request, PurchaseOptionPromptMode.LIGHT));
        assertOrdered(PurchaseOptionAiService.schema(request, PurchaseOptionPromptMode.FULL));
    }

    void assertOrdered(Object value) {
        if (value instanceof Map<?,?> map) {
            var keys = map.keySet().stream().map(Object::toString).toList();
            assertThat(keys).isSorted();
            map.values().forEach(this::assertOrdered);
        } else if (value instanceof List<?> list) list.forEach(this::assertOrdered);
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
