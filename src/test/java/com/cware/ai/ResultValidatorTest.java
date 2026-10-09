package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ResultValidatorTest {
    private final ResultValidator validator = new ResultValidator();

    @Test void extractsMultipleAllowedValuesFromOneOriginalName() {
        var request = Fixtures.request();
        var proposal = Fixtures.proposal();
        assertThat(validator.validateProposal(request, proposal)).isEmpty();
        var items = validator.assemble(request, proposal);
        assertThat(validator.validateItems(request, proposal, items)).isEmpty();
        assertThat(items.get(0).purchaseOptions()).containsExactlyInAnyOrderEntriesOf(
                Map.of("핏", "배기핏", "색상", "남색", "사이즈", "100"));
        assertThat(items.get(2).purchaseOptions()).containsEntry("사이즈", "200");
    }

    @Test void trustsAiValuesButRejectsChangesDuringAssembly() {
        var request = Fixtures.request();
        var entries = new ArrayList<>(Fixtures.proposal().mappings());
        entries.set(1, new MappingProposal.Entry("1", "색상", "파랑", .99));
        assertThat(validator.validateProposal(request, new MappingProposal(true, .99, entries, "사유"))).isEmpty();
        var items = new ArrayList<>(validator.assemble(request, Fixtures.proposal()));
        items.set(0, new PurchaseOptionItem("1", Map.of("핏", "배기핏", "색상", "남색", "사이즈", "101")));
        assertThat(validator.validateItems(request, Fixtures.proposal(), items)).isNotEmpty();
    }

    @Test void acceptsSubsetOfAllowedNamesAndRejectsDuplicateTarget() {
        var request = Fixtures.ambiguous();
        var subset = new MappingProposal(true, .99,
                List.of(new MappingProposal.Entry("1", "색상", "남색", .99)), "사유");
        assertThat(validator.validateProposal(request, subset)).isEmpty();
        assertThat(validator.validateItems(request, subset, validator.assemble(request, subset))).isEmpty();
        var duplicate = new MappingProposal(true, .99, List.of(
                new MappingProposal.Entry("1", "색상", "남색", .99),
                new MappingProposal.Entry("1", "색상", "100", .99),
                new MappingProposal.Entry("1", "사이즈", "100", .99)), "사유");
        assertThat(validator.validateProposal(request, duplicate)).anyMatch(error -> error.contains("중복"));
    }

    @Test void rejectsItemWithNoMappings() {
        assertThat(validator.validateProposal(Fixtures.ambiguous(),
                new MappingProposal(true, .99, List.of(), "추출 결과 없음")))
                .anyMatch(error -> error.contains("매핑이 누락"));
    }

    @Test void fillsOnlyMissingOptionsWithoutDefaultUnitsForEachItem() {
        var request = new InferenceRequest("length-product", "구성품 10개", null,
                List.of("길이", "수량", "색상", "중량"),
                List.of(new SourceOption("1", "10개"), new SourceOption("2", "20개 빨강")),
                "구성품 10개", List.of(new PurchaseOptionUnit("중량", "g", List.of("g", "kg"))));
        var proposal = new MappingProposal(true, .99, List.of(
                new MappingProposal.Entry("1", "수량", "10개", .99),
                new MappingProposal.Entry("2", "수량", "20개", .99),
                new MappingProposal.Entry("2", "색상", "빨강", .99)), "수량과 두 번째 단품 색상 추출");
        assertThat(validator.validateProposal(request, proposal)).isEmpty();
        var items = validator.assemble(request, proposal);
        assertThat(items.get(0).purchaseOptions()).containsExactlyInAnyOrderEntriesOf(
                Map.of("길이", "없음", "수량", "10개", "색상", "없음"));
        assertThat(items.get(1).purchaseOptions()).containsExactlyInAnyOrderEntriesOf(
                Map.of("길이", "없음", "수량", "20개", "색상", "빨강"));
        assertThat(validator.validateItems(request, proposal, items)).isEmpty();
    }

    @Test void rejectsUnknownIdsAndDisallowedTargets() {
        for (var entry : List.of(new MappingProposal.Entry("unknown", "색상", "남색", .99),
                new MappingProposal.Entry("1", "허용되지 않은 항목", "남색", .99))) {
            assertThat(validator.validateProposal(Fixtures.ambiguous(),
                    new MappingProposal(true, .99, List.of(entry), "사유"))).isNotEmpty();
        }
    }

    @Test void allowsAiToUseSameValueForDifferentTargets() {
        var request = Fixtures.ambiguous();
        var proposal = new MappingProposal(true, .99, List.of(
                new MappingProposal.Entry("1", "색상", "남색 100", .99),
                new MappingProposal.Entry("1", "사이즈", "남색 100", .99)), "사유");
        assertThat(validator.validateProposal(request, proposal)).isEmpty();
    }

    @ParameterizedTest @ValueSource(doubles={-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidConfidence(double confidence) {
        assertThat(validator.validateProposal(Fixtures.request(),
                new MappingProposal(true, confidence, Fixtures.proposal().mappings(), "사유"))).isNotEmpty();
    }

    @Test void rejectsDuplicateFinalCombinations() {
        var request = Fixtures.withOptions(Fixtures.ambiguous(),
                List.of(new SourceOption("1", "남색 100"), new SourceOption("2", "남색 100")));
        var proposal = new MappingProposal(true, .99, List.of(
                new MappingProposal.Entry("1", "색상", "남색", .99),
                new MappingProposal.Entry("1", "사이즈", "100", .99),
                new MappingProposal.Entry("2", "색상", "남색", .99),
                new MappingProposal.Entry("2", "사이즈", "100", .99)), "사유");
        assertThat(validator.validateProposal(request, proposal)).isEmpty();
        assertThat(validator.validateItems(request, proposal, validator.assemble(request, proposal)))
                .anyMatch(error -> error.contains("동일한 구매옵션 조합"));
    }
}
