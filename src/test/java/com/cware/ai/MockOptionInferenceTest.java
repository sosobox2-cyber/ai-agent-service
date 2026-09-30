package com.cware.ai;

import com.cware.ai.inference.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MockOptionInferenceTest {
    @Test void extractsSampleColorSizeAndFitWithoutCallingAi() {
        var request = Fixtures.request();
        var proposal = MockOptionInferenceService.infer(request);
        assertThat(new ResultValidator().validateProposal(request, proposal)).isEmpty();
        assertThat(new ResultValidator().assemble(request, proposal).get(0).purchaseOptions())
                .containsEntry("핏", "배기핏").containsEntry("색상", "남색").containsEntry("사이즈", "100");
        assertThat(proposal.confidence()).isZero();
    }
}
