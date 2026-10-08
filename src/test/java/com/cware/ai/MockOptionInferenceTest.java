package com.cware.ai;

import com.cware.ai.inference.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MockOptionInferenceTest {
    @Test void multipleScreenSizesAreNotGuessed() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var original = mapper.readValue(java.nio.file.Files.readString(java.nio.file.Path.of("examples/tv-request.json")),
                com.cware.ai.dto.InferenceRequest.class);
        var request = new com.cware.ai.dto.InferenceRequest(original.goodsId(),
                "TV 109cm(43인치) 또는 127cm(50인치)", original.categoryName(), original.allowedPurchaseOptions(),
                original.options(), original.productNoticeText());
        assertThat(MockOptionInferenceService.infer(request).mappings()).isEmpty();
    }
    @Test void extractsSampleColorSizeAndFitWithoutCallingAi() {
        var request = Fixtures.request();
        var proposal = MockOptionInferenceService.infer(request);
        assertThat(new ResultValidator().validateProposal(request, proposal)).isEmpty();
        assertThat(new ResultValidator().assemble(request, proposal).get(0).purchaseOptions())
                .containsEntry("핏", "배기핏").containsEntry("색상", "남색").containsEntry("사이즈", "100");
        assertThat(proposal.confidence()).isZero();
    }
}
