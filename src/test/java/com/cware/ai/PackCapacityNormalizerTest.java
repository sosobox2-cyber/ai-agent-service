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
        var source = new Calculation.Evidence("productCompositionText", "50ml×30포×4세트");
        var count = new MappingProposal.Entry("1", "수량", "4세트", .9, source.source(), "4세트",
                new Calculation(Calculation.Operation.PACK_COUNT, "세트", List.of(
                        new Calculation.Operand("4", "세트", new Calculation.Evidence(source.source(), "4세트"))), source));
        var volume = new MappingProposal.Entry("1", "개당 용량", "50ml", .9, source.source(), "50ml",
                new Calculation(Calculation.Operation.DIRECT, "ml", List.of(
                        new Calculation.Operand("50", "ml", new Calculation.Evidence(source.source(), "50ml"))), null));
        var facts = List.of(new Fact("item", "1", "main", Role.MAIN, Measure.VOLUME, Scope.ITEM,
                "50", "ml", source.source(), "50ml"),
                new Fact("outer", "1", "main", Role.MAIN, Measure.COUNT, Scope.TOTAL,
                        "4", "세트", source.source(), "4세트"));
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
}
