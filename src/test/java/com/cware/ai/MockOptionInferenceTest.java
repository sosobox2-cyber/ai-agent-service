package com.cware.ai;

import com.cware.ai.inference.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MockOptionInferenceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"80매x6롤", "80매 X 6롤", "80매×6롤", "80매*6롤"})
    void sheetRollMockParsesBothCountsWithAllMultiplicationSeparators(String composition) {
        var request = new com.cware.ai.dto.InferenceRequest("other-towel", "키친타올 " + composition, null,
                java.util.List.of("개당 수량", "수량"), java.util.List.of(new com.cware.ai.dto.SourceOption("1", "단품")),
                "제조국: 한국", java.util.List.of(
                        new com.cware.ai.dto.PurchaseOptionUnit("개당 수량", "개", java.util.List.of("매")),
                        new com.cware.ai.dto.PurchaseOptionUnit("수량", "개", java.util.List.of("개"))));
        var proposal = MockOptionInferenceService.infer(request);
        var validator = new ResultValidator();
        assertThat(validator.validateProposal(request, proposal)).isEmpty();
        assertThat(validator.assemble(request, proposal).get(0).purchaseOptions()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("개당 수량", "80매", "수량", "6개"));
    }

    @Test void sheetRollMockDoesNotUseConflictingOrOuterPackCountsAsSalesRollCount() {
        for (String composition : java.util.List.of("140매x12롤x4팩", "140매x12롤 또는 80매x6롤")) {
            var request = new com.cware.ai.dto.InferenceRequest("towel", composition, null,
                    java.util.List.of("개당 수량"), java.util.List.of(new com.cware.ai.dto.SourceOption("1", "단품")),
                    "제조국: 한국");
            assertThat(MockOptionInferenceService.infer(request).mappings()).isEmpty();
        }
    }
    @Test void packagedWeightMockUsesMainProductWeightAndCountWithoutBonusOrConstants() {
        var request = new com.cware.ai.dto.InferenceRequest("other-cream", "영양크림 80g 5통 + 무료체험 20g 1통", null,
                java.util.List.of("개당 중량", "수량"), java.util.List.of(new com.cware.ai.dto.SourceOption("1", "단품")),
                "용량 또는 중량: 80g / 20g", java.util.List.of(
                        new com.cware.ai.dto.PurchaseOptionUnit("수량", "개", java.util.List.of("개"))));
        var proposal = MockOptionInferenceService.infer(request);
        var validator = new ResultValidator();
        assertThat(validator.validateProposal(request, proposal)).isEmpty();
        assertThat(validator.assemble(request, proposal).get(0).purchaseOptions()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("개당 중량", "80g", "수량", "5개"));
        assertThat(proposal.mappings().get(0).evidenceText()).doesNotContain("20g");
        assertThat(proposal.mappings().get(1).calculation().operands().get(0).unit()).isEqualTo("통");
    }

    @Test void packagedWeightMockDoesNotGuessConflictingWeightOrMultipleOptionWeights() {
        var conflict = new com.cware.ai.dto.InferenceRequest("cream", "120g 3통", null,
                java.util.List.of("개당 중량"), java.util.List.of(new com.cware.ai.dto.SourceOption("1", "단일상품")),
                "130g 3통");
        assertThat(MockOptionInferenceService.infer(conflict).mappings()).isEmpty();
        var multiple = new com.cware.ai.dto.InferenceRequest("cream", "120g 3통", null,
                java.util.List.of("개당 중량"), java.util.List.of(new com.cware.ai.dto.SourceOption("1", "단일상품"),
                        new com.cware.ai.dto.SourceOption("2", "단일상품")), "120g 3통");
        assertThat(MockOptionInferenceService.infer(multiple).mappings()).isEmpty();
    }
    @Test void rollPackMockUsesSourceCountsAndConfiguredUnitsRatherThanExampleConstants() {
        var request = new com.cware.ai.dto.InferenceRequest("other-roll-product", "45cm × 12롤 × 3팩(총 36롤)", null,
                java.util.List.of("길이", "개당 수량", "수량"),
                java.util.List.of(new com.cware.ai.dto.SourceOption("1", "단품")), "제조국: 한국",
                java.util.List.of(new com.cware.ai.dto.PurchaseOptionUnit("개당 수량", "개", java.util.List.of("개입")),
                        new com.cware.ai.dto.PurchaseOptionUnit("수량", "개", java.util.List.of("팩"))));
        var proposal = MockOptionInferenceService.infer(request);
        var validator = new ResultValidator();
        assertThat(validator.validateProposal(request, proposal)).isEmpty();
        assertThat(validator.assemble(request, proposal).get(0).purchaseOptions()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("길이", "45cm", "개당 수량", "12개", "수량", "3팩"));
        assertThat(proposal.mappings().get(1).calculation().operands().get(0).unit()).isEqualTo("롤");
    }

    @Test void rollPackMockDoesNotAssignCommonDimensionsToMultipleOptions() {
        var request = new com.cware.ai.dto.InferenceRequest("roll-product", "22m x 30롤 x 4팩(총 120롤)", null,
                java.util.List.of("길이", "개당 수량"),
                java.util.List.of(new com.cware.ai.dto.SourceOption("1", "단일상품"),
                        new com.cware.ai.dto.SourceOption("2", "단일상품")), "제조국: 한국");
        assertThat(MockOptionInferenceService.infer(request).mappings()).isEmpty();
    }

    @Test void rollPackMockDoesNotGuessWhenSourcesOrTotalCountsConflict() {
        for (String notice : java.util.List.of("22m x 20롤 x 4팩(총 80롤)", "22m x 30롤 x 4팩(총 100롤)")) {
            var request = new com.cware.ai.dto.InferenceRequest("roll-product", "22m x 30롤 x 4팩(총 120롤)", null,
                    java.util.List.of("길이", "개당 수량"),
                    java.util.List.of(new com.cware.ai.dto.SourceOption("1", "단일상품")), notice);
            assertThat(MockOptionInferenceService.infer(request).mappings()).isEmpty();
        }
    }
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
