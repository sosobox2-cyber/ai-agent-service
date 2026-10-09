package com.cware.ai;

import com.cware.ai.inference.*;
import com.cware.ai.dto.Calculation;
import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InferenceServiceTest {
    final OptionInferenceGateway ai = mock(OptionInferenceGateway.class);
    final PurchaseOptionInferenceService service = new PurchaseOptionInferenceService(new RequestValidator(),
            ai, new ResultValidator(), new InputHashService(new ObjectMapper()), Fixtures.properties());

    @BeforeEach void delegateUsageOverloadToExistingMockProposals() {
        when(ai.infer(any(), any())).thenAnswer(invocation -> ai.infer(invocation.getArgument(0)));
    }

    @Test void acceptsAiColorCountReasoningAndReturnsUnmodifiedValueAndEvidence() throws Exception {
        var request = new ObjectMapper().readValue(Files.readString(Path.of("examples/set-request.json")), InferenceRequest.class);
        var packEvidence = new Calculation.Evidence("productNoticeText", "[해즈픽] 시그니처 케어 칫솔 10개 세트");
        var colorEvidence = new Calculation.Evidence("productNoticeText", "컬러: 민트, 핑크, 오렌지, 그레이, 블루 2개씩");
        var quantity = new MappingProposal.Entry("1", "수량", "1세트", .90,
                packEvidence.source(), packEvidence.text(),
                new Calculation(Calculation.Operation.PACK_COUNT, "세트", List.of(), packEvidence));
        var contents = new MappingProposal.Entry("1", "개당 수량", "10매입", .90,
                colorEvidence.source(), colorEvidence.text(),
                new Calculation(Calculation.Operation.PACK_CONTENT, "매입",
                        List.of(new Calculation.Operand("10", "개", colorEvidence)), colorEvidence));
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .90, List.of(quantity, contents),
                "색상 5가지가 각 2개씩 있어 한 세트에 총 10개입니다."));

        var result = service.infer(request);
        assertThat(result.success()).isTrue();
        assertThat(result.autoApplyCandidate()).isTrue();
        assertThat(result.validationErrors()).isEmpty();
        assertThat(result.serverAssessment().decisionCode()).isEqualTo("ACCEPTED");
        assertThat(result.items().get(0).purchaseOptions()).containsEntry("수량", "1세트").containsEntry("개당 수량", "10매입");
        assertThat(result.optionMappings().get(1).value()).isEqualTo("10매입");
        assertThat(result.optionMappings().get(1).evidenceText()).isEqualTo(colorEvidence.text());
        assertThat(result.optionMappings().get(1).calculation()).isEqualTo(contents.calculation());
        assertThat(result.aiAssessment().mappings().get(1).calculation()).isEqualTo(contents.calculation());
    }

    @Test void aiExtractsSeveralAllowedTargetsPerItem() {
        when(ai.infer(any())).thenReturn(Fixtures.proposal());
        var result = service.infer(Fixtures.request());
        assertThat(result.success()).isTrue();
        assertThat(result.autoApplyCandidate()).isTrue();
        assertThat(result.inferenceSource()).isEqualTo("AI");
        assertThat(result.optionMappings()).hasSize(9);
        assertThat(result.items().get(0).purchaseOptions()).containsEntry("색상", "남색").containsEntry("사이즈", "100");
        verify(ai).infer(any());
    }

    @Test void allowedNamesDoNotRequireEveryNameToBeMapped() {
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .99,
                List.of(new MappingProposal.Entry("1", "색상", "남색", .99)), "색상만 확실하게 추출"));
        var result = service.infer(Fixtures.ambiguous());
        assertThat(result.success()).isTrue();
        assertThat(result.autoApplyCandidate()).isTrue();
        assertThat(result.validationErrors()).isEmpty();
        assertThat(result.items().get(0).purchaseOptions()).containsExactlyInAnyOrderEntriesOf(Map.of("색상", "남색", "사이즈", "없음"));
        assertThat(result.optionMappings()).hasSize(1);
        assertThat(result.aiAssessment().mappings()).hasSize(1);
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
        assertThat(result.serverAssessment().decisionCode()).isEqualTo("LOW_CONFIDENCE");
        assertThat(result.serverAssessment().confidenceThreshold()).isEqualTo(.80);
        assertThat(result.serverAssessment().reason()).contains("0.45", "0.80");
        assertThat(result.aiAssessment().confidence()).isEqualTo(.99);
        assertThat(result.aiAssessment().mappings().get(1).value()).isEqualTo("100");
        assertThat(result.aiAssessment().mappings().get(1).confidence()).isEqualTo(.45);
    }

    @Test void invalidAiOutputIsNeverApplied() {
        when(ai.infer(any())).thenReturn(Fixtures.proposal());
        var result = service.infer(Fixtures.ambiguous());
        assertThat(result.success()).isFalse();
        assertThat(result.validationErrors()).isNotEmpty();
        assertThat(result.items()).isEmpty();
        assertThat(result.serverAssessment().decisionCode()).isEqualTo("RESULT_VALIDATION_FAILED");
        assertThat(result.aiAssessment().mappings()).hasSize(9);
    }

    @Test void uncertainResponseKeepsReasonWithoutInventingMappings() {
        when(ai.infer(any())).thenReturn(new MappingProposal(false, .45, List.of(), "의미를 판단할 수 없음"));
        var result = service.infer(Fixtures.ambiguous());
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.items()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0.80, 0.80", "0.80, 0.90", "0.90, 0.80"})
    void thresholdBoundaryIsInclusive(double overallConfidence, double mappingConfidence) {
        when(ai.infer(any())).thenReturn(new MappingProposal(true, overallConfidence, List.of(
                new MappingProposal.Entry("1", "색상", "남색", .90),
                new MappingProposal.Entry("1", "사이즈", "100", mappingConfidence)), "판단 가능"));
        var result = service.infer(Fixtures.ambiguous());
        assertThat(result.success()).isTrue();
        assertThat(result.autoApplyCandidate()).isTrue();
        assertThat(result.confidence()).isEqualTo(.80);
        assertThat(result.serverAssessment().decisionCode()).isEqualTo("ACCEPTED");
        assertThat(result.validationErrors()).isEmpty();
        assertThat(result.items()).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0.7999, 0.90", "0.90, 0.7999"})
    void confidenceJustBelowThresholdRequiresReview(double overallConfidence, double mappingConfidence) {
        when(ai.infer(any())).thenReturn(new MappingProposal(true, overallConfidence, List.of(
                new MappingProposal.Entry("1", "색상", "남색", mappingConfidence)), "판단 가능"));
        var result = service.infer(Fixtures.ambiguous());
        assertThat(result.success()).isFalse();
        assertThat(result.autoApplyCandidate()).isFalse();
        assertThat(result.serverAssessment().decisionCode()).isEqualTo("LOW_CONFIDENCE");
        assertThat(result.items()).isEmpty();
    }

    @Test void confidencePointNineNowPassesWhenCertainAndValidated() {
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .90, List.of(
                new MappingProposal.Entry("1", "색상", "남색", .90),
                new MappingProposal.Entry("1", "사이즈", "100", .90)), "판단 가능"));
        var result = service.infer(Fixtures.ambiguous());
        assertThat(result.success()).isTrue();
        assertThat(result.autoApplyCandidate()).isTrue();
        assertThat(result.items()).isNotEmpty();
    }

    @Test void individualConfidenceBelowThresholdStillRequiresReview() {
        when(ai.infer(any())).thenReturn(new MappingProposal(true, .90, List.of(
                new MappingProposal.Entry("1", "색상", "남색", .90),
                new MappingProposal.Entry("1", "사이즈", "100", .79)), "사이즈 판단 불확실"));
        var result = service.infer(Fixtures.ambiguous());
        assertThat(result.confidence()).isEqualTo(.79);
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.autoApplyCandidate()).isFalse();
        assertThat(result.items()).isEmpty();
    }

    @Test void uncertainProposalStillRequiresReviewAboveThreshold() {
        when(ai.infer(any())).thenReturn(new MappingProposal(false, .90, List.of(
                new MappingProposal.Entry("1", "색상", "남색", .90),
                new MappingProposal.Entry("1", "사이즈", "100", .90)), "판단 불확실"));
        var result = service.infer(Fixtures.ambiguous());
        assertThat(result.validationErrors()).isEmpty();
        assertThat(result.errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.autoApplyCandidate()).isFalse();
        assertThat(result.items()).isEmpty();
        assertThat(result.serverAssessment().decisionCode()).isEqualTo("AI_UNCERTAIN");
        assertThat(result.aiAssessment().certain()).isFalse();
        assertThat(result.aiAssessment().mappings()).hasSize(2);
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
