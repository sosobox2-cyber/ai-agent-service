package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductCompositionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private InferenceRequest request(String composition) {
        return new InferenceRequest("composition-test", "선크림", null, "뷰티", "1", "선크림",
                List.of("수량", "개당 용량"), List.of(new SourceOption("1", "단일상품")),
                null, List.of(), composition);
    }

    @Test void acceptsOmittedCompositionAndEnforcesMaximumLength() throws Exception {
        var json = mapper.writeValueAsString(request(null));
        assertThat(mapper.readValue(json.replace(",\"productCompositionText\":null", ""), InferenceRequest.class)
                .productCompositionText()).isNull();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(request(null))).isEmpty();
            assertThat(validator.validate(request("가".repeat(20000)))).isEmpty();
            assertThat(validator.validate(request("가".repeat(20001))))
                    .anyMatch(v -> v.getPropertyPath().toString().equals("productCompositionText"));
        }
    }

    @Test void compositionChangesHash() {
        var hashes = new InputHashService(mapper);
        assertThat(hashes.hash(request("본품 7개"))).isNotEqualTo(hashes.hash(request(null)))
                .isNotEqualTo(hashes.hash(request("본품 8개")));
    }

    @Test void mockReadsCompositionAndExcludesBonus() {
        var request = request("본품 선크림 50ml 7개 + 사은품 2개");
        assertThat(QuantityContext.mockEntry(request, "수량").value()).isEqualTo("7개");
        assertThat(QuantityContext.mockEntry(request, "개당 용량").value()).isEqualTo("50ml");
        assertThat(QuantityContext.mockEntry(request, "수량").evidenceSource())
                .isEqualTo("productCompositionText");
    }

    @Test void compositionIsSentToAiAndReturnedAsEvidence() throws Exception {
        var request = request("본품 선크림 50ml 7개 + 사은품 2개");
        var evidence = new Calculation.Evidence("productCompositionText", "7개");
        var entry = new MappingProposal.Entry("1", "수량", "7개", .95, evidence.source(), evidence.text(),
                new Calculation(Calculation.Operation.DIRECT, "개",
                        List.of(new Calculation.Operand("7", "개", evidence)), null));
        var proposal = new MappingProposal(true, .95, List.of(entry), "기술서의 본품 수량을 판단했습니다.");
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage(mapper.writeValueAsString(proposal)),
                ChatGenerationMetadata.builder().finishReason("stop").build()))));
        assertThat(new PurchaseOptionAiService(model, mapper).infer(request)).isEqualTo(proposal);
        var capture = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(capture.capture());
        assertThat(capture.getValue().getInstructions().get(1).getText())
                .contains("\"productCompositionText\":\"" + request.productCompositionText() + "\"");
        assertThat(mapper.writeValueAsString(PurchaseOptionAiService.schema(request)))
                .contains("productCompositionText");
    }
}
