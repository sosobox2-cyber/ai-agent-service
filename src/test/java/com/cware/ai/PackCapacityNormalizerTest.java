package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.dto.QuantityEvidence.*;
import com.cware.ai.inference.*;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PackCapacityNormalizerTest {
    private InferenceRequest request(String notice, String composition) {
        return new InferenceRequest("packed-volume", "음료 4세트", null, List.of("수량", "개당 용량"),
                List.of(new SourceOption("1", "단일상품")), notice,
                List.of(new PurchaseOptionUnit("수량", "개", List.of("개", "박스", "세트")),
                        new PurchaseOptionUnit("개당 용량", "ml", List.of("ml", "L"))), composition);
    }
    private MappingProposal aiProposal() {
        return aiProposal("50ml×30포×4세트", "50", "ml", "50ml", "30포", "4세트");
    }
    private MappingProposal aiProposal(String expression, String itemAmount, String itemUnit,
                                       String itemQuote, String contentQuote, String outerQuote) {
        var source = new Calculation.Evidence("productCompositionText", expression);
        var count = new MappingProposal.Entry("1", "수량", "4세트", .9, source.source(), outerQuote,
                new Calculation(Calculation.Operation.PACK_COUNT, "세트", List.of(
                        new Calculation.Operand("4", "세트", new Calculation.Evidence(source.source(), outerQuote))), source));
        var volume = new MappingProposal.Entry("1", "개당 용량", itemAmount + itemUnit, .9, source.source(), itemQuote,
                new Calculation(Calculation.Operation.DIRECT, itemUnit, List.of(
                        new Calculation.Operand(itemAmount, itemUnit, new Calculation.Evidence(source.source(), itemQuote))), null));
        var facts = List.of(new Fact("item", "1", "main", Role.MAIN, Measure.VOLUME, Scope.ITEM,
                itemAmount, itemUnit, source.source(), itemQuote),
                new Fact("outer", "1", "main", Role.MAIN, Measure.COUNT, Scope.TOTAL,
                        "4", "세트", source.source(), outerQuote));
        var reviews = List.of(new SourceReview("1", "goodsName", Status.NO_RELEVANT_FACT),
                new SourceReview("1", "productNoticeText", Status.NO_RELEVANT_FACT),
                new SourceReview("1", "productCompositionText", Status.EXTRACTED),
                new SourceReview("1", "optionName1", Status.NO_RELEVANT_FACT));
        return new MappingProposal(true, .9, List.of(count, volume), "원문 포장 구성",
                new QuantityEvidence(facts, null, reviews));
    }

    @Test void computesAndApprovesCapacityPerSalePack() {
        var request = request("상품 정보", "50ml×30포×4세트");
        var raw = aiProposal();
        assertThat(new ResultValidator().validateAiProposal(request, raw))
                .anyMatch(e -> e.startsWith("QUANTITY_LEVEL_MISMATCH"));
        var normalized = PackCapacityNormalizer.normalize(request, raw);
        assertThat(normalized.mappings()).extracting(MappingProposal.Entry::value).containsExactly("4세트", "1500ml");
        assertThat(normalized.mappings().get(1).calculation().operation()).isEqualTo(Calculation.Operation.PACK_CAPACITY);
        assertThat(normalized.mappings().get(1).calculation().operands()).hasSize(2);
        assertThat(normalized.quantityEvidence().facts()).anyMatch(f -> f.scope() == Scope.PACK
                && "30".equals(f.amount()) && "포".equals(f.unit()));
        assertThat(new ResultValidator().validateAiProposal(request, normalized)).isEmpty();
        var ai = mock(OptionInferenceGateway.class);
        when(ai.infer(any(), any())).thenReturn(raw);
        var service = new PurchaseOptionInferenceService(new RequestValidator(), ai,
                new ResultValidator(), new InputHashService(new ObjectMapper()), Fixtures.properties());
        var response = service.infer(request);
        assertThat(response.success()).isTrue();
        assertThat(response.items().get(0).purchaseOptions())
                .containsEntry("수량", "4세트").containsEntry("개당 용량", "1500ml");
    }

    @Test void rejectsTamperedCalculationMissingFactAndConflictingSource() {
        var request = request("상품 정보", "50ml×30포×4세트");
        var normalized = PackCapacityNormalizer.normalize(request, aiProposal());
        var wrong = new MappingProposal.Entry("1", "개당 용량", "1400ml", .9,
                "productCompositionText", "50ml×30포×4세트", normalized.mappings().get(1).calculation());
        var changed = new MappingProposal(true, .9, List.of(normalized.mappings().get(0), wrong), "틀린 계산",
                normalized.quantityEvidence());
        assertThat(new ResultValidator().validateAiProposal(request, changed))
                .anyMatch(e -> e.startsWith("QUANTITY_PACK_CAPACITY_INVALID"));
        var missing = new MappingProposal(true, .9, normalized.mappings(), normalized.reason(),
                new QuantityEvidence(normalized.quantityEvidence().facts().stream()
                        .filter(f -> f.scope() != Scope.PACK).toList(), null,
                        normalized.quantityEvidence().sourceReviews()));
        assertThat(new ResultValidator().validateAiProposal(request, missing))
                .anyMatch(e -> e.startsWith("QUANTITY_PACK_CAPACITY_INVALID"));
        var conflict = request("다른 용량 40ml", "50ml×30포×4세트");
        assertThat(PackCapacityNormalizer.normalize(conflict, aiProposal())).isEqualTo(aiProposal());
        assertThat(new ResultValidator().validateAiProposal(conflict, aiProposal()))
                .anyMatch(e -> e.startsWith("QUANTITY_LEVEL_MISMATCH"));
    }

    @Test void repairsExplicitOuterPackOperandWhenAiLeavesItEmpty() {
        var request = request("상품 정보", "50ml×30포×4세트");
        var correct = PackCapacityNormalizer.normalize(request, aiProposal());
        var outer = correct.mappings().get(0);
        var omitted = new MappingProposal.Entry("1", "수량", "4세트", .9, "productCompositionText", "4세트",
                new Calculation(Calculation.Operation.PACK_COUNT, "세트", List.of(),
                        new Calculation.Evidence("productCompositionText", "50ml×30포×4세트")));
        var proposal = new MappingProposal(true, .9, List.of(omitted, correct.mappings().get(1)),
                correct.reason(), correct.quantityEvidence());
        assertThat(new ResultValidator().validateAiProposal(request, proposal)).isNotEmpty();
        var fixed = PackCapacityNormalizer.normalize(request, proposal);
        assertThat(fixed.mappings().get(0).calculation().operands()).hasSize(1);
        assertThat(fixed.mappings().get(0).value()).isEqualTo(outer.value());
        assertThat(new ResultValidator().validateAiProposal(request, fixed)).isEmpty();
    }

    @Test void acceptsEquivalentMultiplicationMarksAndRejectsConflictingSources() {
        for (String expression : List.of("50ml x 30포 x 4세트", "50ml * 30포 * 4세트")) {
            var raw = aiProposal();
            var count = raw.mappings().get(0);
            var old = count.calculation();
            var revised = new MappingProposal.Entry(count.optionId(), count.targetPurchaseOptionName(),
                    count.value(), count.confidence(), count.evidenceSource(), count.evidenceText(),
                    new Calculation(old.operation(), old.outputUnit(), old.operands(),
                            new Calculation.Evidence("productCompositionText", expression)));
            var proposal = new MappingProposal(raw.certain(), raw.confidence(),
                    List.of(revised, raw.mappings().get(1)), raw.reason(), raw.quantityEvidence());
            var request = request("상품 정보", expression);
            assertThat(new ResultValidator().validateAiProposal(request,
                    PackCapacityNormalizer.normalize(request, proposal))).isEmpty();
            assertThat(PackCapacityNormalizer.normalize(request("다른 용량 40ml", expression), proposal))
                    .isEqualTo(proposal);
        }
    }

    @Test void computesCapacityWithSpacesInsideUnits() {
        String expression = "50 ml × 30 포 × 4 세트";
        var request = request("상품 정보", expression);
        var proposal = aiProposal(expression, "50", "ml", "50 ml", "30 포", "4 세트");
        var normalized = PackCapacityNormalizer.normalize(request, proposal);
        assertThat(normalized.mappings().get(1).value()).isEqualTo("1500ml");
        assertThat(new ResultValidator().validateAiProposal(request, normalized)).isEmpty();
    }

    @Test void convertsSubunitLiterExpressionAndChecksEquivalentSource() {
        String expression = "0.05L×30포×4세트";
        var request = request("동일 본품 50ml", expression);
        var proposal = aiProposal(expression, "0.05", "L", "0.05L", "30포", "4세트");
        assertThat(new ResultValidator().validateAiProposal(request, proposal))
                .anyMatch(e -> e.startsWith("QUANTITY_LEVEL_MISMATCH"));
        var normalized = PackCapacityNormalizer.normalize(request, proposal);
        assertThat(normalized.mappings().get(1).value()).isEqualTo("1.5L");
        assertThat(new ResultValidator().validateAiProposal(request, normalized)).isEmpty();
        var conflict = request("상충하는 본품 60ml", expression);
        assertThat(PackCapacityNormalizer.normalize(conflict, proposal)).isEqualTo(proposal);
        assertThat(new ResultValidator().validateAiProposal(conflict, proposal))
                .anyMatch(e -> e.startsWith("QUANTITY_LEVEL_MISMATCH"));
    }
}
