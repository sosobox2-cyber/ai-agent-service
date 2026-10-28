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

class ImplicitSingleQuantityPolicyTest {
    @Test void approvesOneForAQuantityLessSingleProductWhilePreservingOtherMapping() {
        var request = new InferenceRequest("single", "가자미 세꼬시 300g", null,
                List.of("수량", "수산물 중량"), List.of(new SourceOption("001", "단일상품")),
                "가자미 세꼬시 300g", List.of(new PurchaseOptionUnit("수량", "개", List.of("개")),
                        new PurchaseOptionUnit("수산물 중량", "g", List.of("g"))), "가자미 세꼬시 300g");
        var weight = new Fact("weight", "001", "main", Role.MAIN, Measure.WEIGHT, Scope.ITEM,
                "300", "g", "productNoticeText", "가자미 세꼬시 300g");
        var reviews = List.of(new SourceReview("001", "goodsName", Status.EXTRACTED),
                new SourceReview("001", "productNoticeText", Status.EXTRACTED),
                new SourceReview("001", "productCompositionText", Status.EXTRACTED),
                new SourceReview("001", "optionName1", Status.NO_RELEVANT_FACT));
        var badCount = new MappingProposal.Entry("001", "수량", "1개", .9, "goodsName", "단일상품",
                new Calculation(Calculation.Operation.DIRECT, "개", List.of(new Calculation.Operand("1", "개",
                        new Calculation.Evidence("goodsName", "단일상품"))), null));
        var other = new MappingProposal.Entry("001", "수산물 중량", "300g", .95, "productNoticeText", "300g",
                new Calculation(Calculation.Operation.DIRECT, "g", List.of(new Calculation.Operand("300", "g",
                        new Calculation.Evidence("productNoticeText", "300g"))), null));
        var original = new MappingProposal(true, .9, List.of(badCount, other), "단일상품과 중량",
                new QuantityEvidence(List.of(weight), null, reviews));
        var ai = mock(OptionInferenceGateway.class);
        when(ai.infer(any(), any())).thenReturn(original);
        var service = new PurchaseOptionInferenceService(new RequestValidator(), ai,
                new ResultValidator(), new InputHashService(new ObjectMapper()), Fixtures.properties());
        var result = service.infer(request);
        assertThat(result.success()).isTrue();
        assertThat(result.items().get(0).purchaseOptions()).containsEntry("수량", "1개")
                .containsEntry("수산물 중량", "300g");
        assertThat(result.optionMappings()).extracting(OptionMapping::evidenceSource).contains("optionName1");
        assertThat(result.optionMappings()).filteredOn(m -> m.targetPurchaseOptionName().equals("수량"))
                .extracting(OptionMapping::serverNormalization).containsExactly("IMPLICIT_SINGLE_QUANTITY");
        assertThat(result.optionMappings()).filteredOn(m -> m.targetPurchaseOptionName().equals("수산물 중량"))
                .extracting(OptionMapping::serverNormalization).containsOnlyNulls();
        assertThat(result.aiAssessment().quantityEvidence().facts()).anyMatch(f -> f.amount() == null
                && f.unit().equals("개") && f.source().equals("optionName1"));
        assertThat(result.inferenceSource()).isEqualTo("AI");
    }

    @Test void excludesExplicitCountsSetsMultipleOptionsAndUnsupportedUnits() {
        var normal = new InferenceRequest("single", "가자미 300g", null, List.of("수량"),
                List.of(new SourceOption("1", "단일상품")), "중량 300g",
                List.of(new PurchaseOptionUnit("수량", "개", List.of("개"))));
        assertThat(ImplicitSingleQuantityPolicy.eligible(normal)).isTrue();
        for (String name : List.of("9매x4팩", "3+1박스", "3 + 1", "2개짜리", "한 박스", "칫솔 세트", "50ml×30포×4세트"))
            assertThat(ImplicitSingleQuantityPolicy.eligible(new InferenceRequest("other", name, null,
                    normal.allowedPurchaseOptions(), normal.options(), normal.productNoticeText(), normal.purchaseOptionUnits())))
                    .as(name).isFalse();
        assertThat(ImplicitSingleQuantityPolicy.eligible(new InferenceRequest("many", "가자미 300g", null,
                normal.allowedPurchaseOptions(), List.of(new SourceOption("1", "단일상품"), new SourceOption("2", "단일상품")),
                normal.productNoticeText(), normal.purchaseOptionUnits()))).isFalse();
        assertThat(ImplicitSingleQuantityPolicy.eligible(new InferenceRequest("unit", "가자미 300g", null,
                normal.allowedPurchaseOptions(), normal.options(), normal.productNoticeText(),
                List.of(new PurchaseOptionUnit("수량", "박스", List.of("박스")))))).isFalse();
    }

    @Test void keepsPhysicalEvidenceAndRejectsImplicitOneWhenAnExplicitPackExists() {
        var request = new InferenceRequest("weight", "원물 1kg", null, List.of("수량", "개당 중량"),
                List.of(new SourceOption("1", "단일상품")), "원물 1kg",
                List.of(new PurchaseOptionUnit("수량", "개", List.of("개")),
                        new PurchaseOptionUnit("개당 중량", "g", List.of("g", "kg"))));
        var fact = new Fact("weight", "1", "main", Role.MAIN, Measure.WEIGHT, Scope.ITEM,
                "1", "kg", "goodsName", "1kg");
        var weight = new MappingProposal.Entry("1", "개당 중량", "1000g", .9, "goodsName", "1kg",
                new Calculation(Calculation.Operation.CONVERT, "g", List.of(new Calculation.Operand("1", "kg",
                        new Calculation.Evidence("goodsName", "1kg"))), null));
        var context = new QuantityEvidence(List.of(fact), null, List.of(
                new SourceReview("1", "goodsName", Status.EXTRACTED),
                new SourceReview("1", "productNoticeText", Status.NO_RELEVANT_FACT),
                new SourceReview("1", "optionName1", Status.NO_RELEVANT_FACT)));
        var normalized = ImplicitSingleQuantityPolicy.normalize(request,
                new MappingProposal(true, .9, List.of(weight), "원문 중량", context));
        assertThat(new ResultValidator().validateAiProposal(request, normalized)).isEmpty();
        assertThat(normalized.mappings()).extracting(MappingProposal.Entry::value).contains("1000g", "1개");
        var withPack = new InferenceRequest("weight-pack", "원물 1kg 2팩", null,
                request.allowedPurchaseOptions(), request.options(), request.productNoticeText(), request.purchaseOptionUnits());
        assertThat(ImplicitSingleQuantityPolicy.normalize(withPack, normalized)).isEqualTo(normalized);
        assertThat(new ResultValidator().validateAiProposal(withPack, normalized))
                .anyMatch(e -> e.startsWith("QUANTITY_EVIDENCE_INVALID"));
    }

    @Test void doesNotOverrideAiUncertaintyOrMockMode() {
        var request = new InferenceRequest("single", "가자미 300g", null, List.of("수량"),
                List.of(new SourceOption("1", "단일상품")), "중량 300g",
                List.of(new PurchaseOptionUnit("수량", "개", List.of("개"))));
        var ai = mock(OptionInferenceGateway.class);
        when(ai.infer(any(), any())).thenReturn(new MappingProposal(false, .9, List.of(), "다른 사유로 불확실"));
        var service = new PurchaseOptionInferenceService(new RequestValidator(), ai,
                new ResultValidator(), new InputHashService(new ObjectMapper()), Fixtures.properties());
        assertThat(service.infer(request).success()).isFalse();
        var mockResult = service.infer(request, true);
        assertThat(mockResult.success()).isFalse();
        assertThat(mockResult.autoApplyCandidate()).isFalse();
        assertThat(mockResult.inferenceSource()).isEqualTo("TEST");
        var ambiguous = new MappingProposal(true, .9, List.of(), "본품 대응 불명확",
                new QuantityEvidence(List.of(), null, List.of(
                        new SourceReview("1", "goodsName", Status.AMBIGUOUS),
                        new SourceReview("1", "productNoticeText", Status.NO_RELEVANT_FACT),
                        new SourceReview("1", "optionName1", Status.NO_RELEVANT_FACT))));
        assertThat(ImplicitSingleQuantityPolicy.normalize(request, ambiguous)).isEqualTo(ambiguous);
    }
}
