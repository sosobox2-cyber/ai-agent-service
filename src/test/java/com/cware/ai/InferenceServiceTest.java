package com.cware.ai;

import com.cware.ai.inference.*;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InferenceServiceTest {
    final OptionInferenceGateway ai = mock(OptionInferenceGateway.class);
    final PurchaseOptionInferenceService service = new PurchaseOptionInferenceService(new RequestValidator(),
            ai, new ResultValidator(), new InputHashService(new ObjectMapper()), Fixtures.properties());

    @Test void aiExtractsSeveralRequiredTargetsPerItem() {
        when(ai.infer(any())).thenReturn(Fixtures.proposal());
        var result = service.infer(Fixtures.request());
        assertThat(result.success()).isTrue();
        assertThat(result.autoApplyCandidate()).isTrue();
        assertThat(result.inferenceSource()).isEqualTo("AI");
        assertThat(result.optionMappings()).hasSize(9);
        assertThat(result.items().get(0).purchaseOptions()).containsEntry("색상", "남색").containsEntry("사이즈", "100");
        verify(ai).infer(any());
    }

    @Test void lowConfidenceNeverExposesApplicableItems() {
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .99, List.of(
                new MappingProposal.Entry("1", "색상", "남색", .99),
                new MappingProposal.Entry("1", "사이즈", "100", .45)), "판단 불확실"));
        var result = service.infer(Fixtures.ambiguous());
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.items()).isEmpty();
        assertThat(result.confidence()).isEqualTo(.45);
    }

    @Test void invalidAiOutputIsNeverApplied() {
        when(ai.infer(any())).thenReturn(Fixtures.proposal());
        var result = service.infer(Fixtures.ambiguous());
        assertThat(result.success()).isFalse();
        assertThat(result.validationErrors()).isNotEmpty();
        assertThat(result.items()).isEmpty();
    }

    @Test void uncertainResponseKeepsReasonWithoutInventingMappings() {
        when(ai.infer(any())).thenReturn(new MappingProposal(false, .45, List.of(), "의미를 판단할 수 없음"));
        var result = service.infer(Fixtures.ambiguous());
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.items()).isEmpty();
    }

    @Test void thresholdBoundaryIsInclusive() {
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .95, List.of(
                new MappingProposal.Entry("1", "색상", "남색", .95),
                new MappingProposal.Entry("1", "사이즈", "100", .95)), "판단 가능"));
        assertThat(service.infer(Fixtures.ambiguous()).success()).isTrue();
    }

    @Test void testModeShowsExtractedValuesWithoutMakingThemApplicable() {
        var result = service.infer(Fixtures.request(), true);
        assertThat(result.inferenceSource()).isEqualTo("TEST");
        assertThat(result.errorCode()).isEqualTo("TEST_MODE");
        assertThat(result.success()).isFalse();
        assertThat(result.autoApplyCandidate()).isFalse();
        assertThat(result.items().get(0).purchaseOptions())
                .containsEntry("핏", "배기핏").containsEntry("색상", "남색").containsEntry("사이즈", "100");
        verifyNoInteractions(ai);
    }
}
