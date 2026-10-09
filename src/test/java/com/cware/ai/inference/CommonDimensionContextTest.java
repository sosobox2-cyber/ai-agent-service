package com.cware.ai.inference;

import com.cware.ai.dto.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class CommonDimensionContextTest {
    @Test void pillowKeepsToleranceForBothColorsAndCounts() throws Exception {
        var request = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                Files.readString(Path.of("examples/pillow-request.json")), InferenceRequest.class);
        var proposal = MockOptionInferenceService.infer(request);
        var validator = new ResultValidator();
        assertThat(validator.validateProposal(request, proposal)).isEmpty();
        var items = validator.assemble(request, proposal);
        assertThat(items).hasSize(2);
        for (var item : items) {
            assertThat(item.purchaseOptions()).containsEntry("사이즈", "43*25cm(+-3cm)").containsEntry("수량", "4개");
        }
        assertThat(items.get(0).purchaseOptions()).containsEntry("색상", "화이트");
        assertThat(items.get(1).purchaseOptions()).containsEntry("색상", "그레이");
        assertThat(validator.validateItems(request, proposal, items)).isEmpty();
    }
    @Test void bothPromptModesPermitOnlyClearCommonProductDimensions() throws Exception {
        for (String suffix : new String[]{"", "-light"}) {
            String prompt = Files.readString(Path.of("src/main/resources/prompts/coupang-purchase-option-system" + suffix + ".txt"));
            assertThat(prompt).contains("43*25cm(+-3cm)", "productNoticeText", "오차 범위", "옵션별 사이즈", "포장 치수가 충돌");
        }
    }
}
