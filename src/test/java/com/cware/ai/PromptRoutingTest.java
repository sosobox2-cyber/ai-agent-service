package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class PromptRoutingTest {
    final ObjectMapper mapper = new ObjectMapper();
    final PurchaseOptionPromptSelector selector = new PurchaseOptionPromptSelector();
    InferenceRequest fixture(String name) throws Exception {
        return mapper.readValue(Files.readString(Path.of("examples/" + name + "-request.json")), InferenceRequest.class);
    }

    @Test void colorAndSizesUseLightButNumericAndUnknownOptionsUseFull() throws Exception {
        assertThat(selector.select(fixture("ai"))).isEqualTo(PurchaseOptionPromptMode.LIGHT);
        for (String name : List.of("quantity", "capacity", "weight", "set", "tv"))
            assertThat(selector.select(fixture(name))).isEqualTo(PurchaseOptionPromptMode.FULL);
        var r = fixture("ai");
        for (String name : List.of("수량", "개당 수량", "개당 용량", "개당 중량", "옵션", "스타일", "화면크기(cm)", "알 수 없는 옵션")) {
            var unknown = new InferenceRequest(r.goodsId(), r.goodsName(), r.categoryName(), List.of("색상", name), r.options(), r.productNoticeText());
            assertThat(selector.select(unknown)).isEqualTo(PurchaseOptionPromptMode.FULL);
        }
    }

    @Test void unitSettingsAndPlaceholderSourceAlwaysUseFull() {
        var r = Fixtures.ambiguous();
        var configured = new InferenceRequest(r.goodsId(), r.goodsName(), r.categoryName(), r.allowedPurchaseOptions(), r.options(), r.productNoticeText(),
                List.of(new PurchaseOptionUnit("사이즈", "호", List.of("호"))));
        assertThat(selector.select(configured)).isEqualTo(PurchaseOptionPromptMode.FULL);
        for (String name : List.of("단품", "단일상품"))
            assertThat(selector.select(Fixtures.withOptions(r, List.of(new SourceOption("1", name)))))
                    .isEqualTo(PurchaseOptionPromptMode.FULL);
    }

    @Test void fullPromptIsOriginalFileAndForceFullIsAvailable() throws Exception {
        var provider = new PurchaseOptionPromptProvider("AUTO");
        assertThat(provider.system(PurchaseOptionPromptMode.FULL))
                .isEqualTo(Files.readString(Path.of("src/main/resources/prompts/coupang-purchase-option-system.txt")));
        assertThat(provider.system(PurchaseOptionPromptMode.LIGHT)).doesNotContain("PACK_COUNT", "PACK_CONTENT", "CONVERT", "SUM");
        assertThat(new PurchaseOptionPromptProvider("FULL").select(fixture("ai"))).isEqualTo(PurchaseOptionPromptMode.FULL);
        assertThatThrownBy(() -> new PurchaseOptionPromptProvider("LIGHT")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void lightMapsAllFourClothingItemsAndUsesSameUserInputAndSchemaAsFull() throws Exception {
        var request = fixture("ai");
        List<MappingProposal.Entry> entries = new ArrayList<>();
        for (SourceOption option : request.options()) {
            entries.add(new MappingProposal.Entry(option.optionId(), "색상", "블랙", .95));
            entries.add(new MappingProposal.Entry(option.optionId(), "패션의류/잡화 사이즈",
                    option.optionName1().split("/")[1], .95));
        }
        var proposal = new MappingProposal(true, .95, entries, "모든 단품의 원문에서 색상과 사이즈를 추출했습니다.");
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage(mapper.writeValueAsString(proposal)),
                ChatGenerationMetadata.builder().finishReason("stop").build()))));
        var ai = new PurchaseOptionAiService(model, mapper);
        var service = new PurchaseOptionInferenceService(new RequestValidator(), ai, new ResultValidator(),
                new InputHashService(mapper), Fixtures.properties());
        var result = service.infer(request);
        assertThat(result.success()).isTrue();
        assertThat(result.items()).hasSize(4);
        assertThat(result.optionMappings()).hasSize(8);
        for (int i = 0; i < 4; i++) assertThat(result.items().get(i).purchaseOptions())
                .containsEntry("색상", "블랙").containsEntry("패션의류/잡화 사이즈", String.valueOf(90 + i * 5));
        new PurchaseOptionAiService(model, mapper, new PurchaseOptionPromptProvider("FULL"), new AiUsageLogger(false)).infer(request);
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(captured.capture());
        var light = captured.getAllValues().get(0);
        var full = captured.getAllValues().get(1);
        assertThat(light.getInstructions().get(0).getText()).doesNotContain("PACK_COUNT");
        assertThat(full.getInstructions().get(0).getText()).contains("PACK_COUNT");
        assertThat(light.getInstructions().get(1).getText()).isEqualTo(full.getInstructions().get(1).getText());
        com.fasterxml.jackson.databind.JsonNode lightOptions = mapper.valueToTree(light.getOptions());
        com.fasterxml.jackson.databind.JsonNode fullOptions = mapper.valueToTree(full.getOptions());
        assertThat(lightOptions).isEqualTo(fullOptions);
    }
}
