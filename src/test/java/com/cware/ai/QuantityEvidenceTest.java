package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.dto.QuantityEvidence.*;
import com.cware.ai.inference.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class QuantityEvidenceTest {
    static InferenceRequest request(List<String> targets, String name, String composition) {
        return new InferenceRequest("quantity-fixture", name, null, targets,
                List.of(new SourceOption("1", "단일상품")), "검증용 정보", List.of(), composition);
    }
    static Fact fact(String id, String component, Measure measure, Scope scope, String amount, String unit, String source, String quote) {
        return new Fact(id, "1", component, Role.MAIN, measure, scope, amount, unit, source, quote);
    }
    static QuantityEvidence context(InferenceRequest request, List<Fact> facts, List<Binding> bindings) {
        List<SourceReview> reviews = new ArrayList<>();
        for (var option : request.options()) for (String source : List.of("goodsName", "productNoticeText", "productCompositionText", "optionName1")) {
            if (source.equals("productCompositionText") && (request.productCompositionText() == null || request.productCompositionText().isBlank())) continue;
            reviews.add(new SourceReview(option.optionId(), source, facts.stream().anyMatch(f -> f.optionId().equals(option.optionId()) && f.source().equals(source))
                    ? Status.EXTRACTED : Status.NO_RELEVANT_FACT));
        }
        return new QuantityEvidence(facts, bindings, reviews);
    }
    static MappingProposal proposal(InferenceRequest request, List<Fact> facts, String target, String value, Calculation.Operation operation, String outputUnit, String... ids) {
        var used = facts.stream().filter(f -> Arrays.asList(ids).contains(f.id())).toList();
        var first = used.get(0);
        var calc = new Calculation(operation, outputUnit, used.stream().filter(f -> f.amount() != null)
                .map(f -> new Calculation.Operand(f.amount(), f.unit(), new Calculation.Evidence(f.source(), f.quote()))).toList(), null);
        var entry = new MappingProposal.Entry("1", target, value, .95, first.source(), first.quote(), calc);
        return new MappingProposal(true, .95, List.of(entry), "원문 사실 확인", context(request, facts,
                List.of(new Binding("1", target, first.componentId(), first.scope(), List.of(ids)))));
    }
    static List<String> errors(InferenceRequest r, MappingProposal p) { return new ResultValidator().validateAiProposal(r, p); }

    @Test void requiresEvidenceOnlyForQuantityAndKeepsLightSchema() throws Exception {
        var r = request(List.of("수량"), "판매 4팩", "4팩");
        var p = new MappingProposal(true, .95, List.of(new MappingProposal.Entry("1", "수량", "4개", .95)), "수량 추출");
        assertThat(errors(r, p)).anyMatch(e -> e.startsWith("QUANTITY_EVIDENCE_REQUIRED"));
        var mapper = new ObjectMapper();
        assertThat(mapper.valueToTree(PurchaseOptionAiService.schema(r)).at("/properties/quantityEvidence/properties/facts/items/properties/scope/enum").size()).isEqualTo(3);
        assertThat(mapper.valueToTree(PurchaseOptionAiService.schema(Fixtures.request(), PurchaseOptionPromptMode.LIGHT))
                .at("/properties/quantityEvidence").isMissingNode()).isTrue();
        assertThat(mapper.readValue(mapper.writeValueAsString(p), MappingProposal.class).quantityEvidence()).isNull();
    }
    @Test void blocksConflictsEvenWhenMappingIsOmittedAndPreservesEvidence() {
        var r = request(List.of("개당 수량", "색상"), "16매", "17매");
        var facts = List.of(fact("a", "main", Measure.COUNT, Scope.PACK, "16", "매", "goodsName", "16매"),
                fact("b", "main", Measure.COUNT, Scope.PACK, "17", "매", "productCompositionText", "17매"));
        var p = new MappingProposal(true, .95, List.of(new MappingProposal.Entry("1", "색상", "흰색", .95)), "색상만 추출",
                context(r, facts, List.of()));
        var response = AiValueContractTest.assess(r, p);
        assertThat(response.success()).isFalse();
        assertThat(response.validationErrors()).anyMatch(e -> e.startsWith("QUANTITY_SOURCE_CONFLICT"));
        assertThat(response.items()).isEmpty();
        assertThat(response.aiAssessment().quantityEvidence()).isEqualTo(p.quantityEvidence());
    }
    @Test void normalizesPhysicalUnitsButDoesNotTreatDifferentLevelsAsConflict() {
        var r = request(List.of("개당 중량"), "1kg", "1000g");
        var facts = List.of(fact("a", "main", Measure.WEIGHT, Scope.ITEM, "1", "kg", "goodsName", "1kg"),
                fact("b", "main", Measure.WEIGHT, Scope.ITEM, "1000", "g", "productCompositionText", "1000g"));
        assertThat(errors(r, proposal(r, facts, "개당 중량", "1000g", Calculation.Operation.CONVERT, "g", "a"))).isEmpty();
        assertThat(errors(r, proposal(r, facts, "개당 중량", "1000g", Calculation.Operation.DIRECT, "g", "a")))
                .anyMatch(e -> e.startsWith("QUANTITY_OPERATION_MISMATCH"));
        r = request(List.of("수량"), "17매 6팩", "17매 6팩");
        facts = List.of(fact("a", "main", Measure.COUNT, Scope.PACK, "17", "매", "goodsName", "17매"),
                fact("b", "main", Measure.COUNT, Scope.TOTAL, "6", "팩", "goodsName", "6팩"));
        assertThat(errors(r, proposal(r, facts, "수량", "6개", Calculation.Operation.PACK_COUNT, "개", "b"))).isEmpty();
    }
    @Test void rejectsMixedItemWeightAndSelectingRepresentativeButAllowsExplicitGift() {
        var r = request(List.of("개당 중량"), "160g 200g", "160g 200g");
        var a = fact("a", "main-a", Measure.WEIGHT, Scope.ITEM, "160", "g", "goodsName", "160g");
        var b = fact("b", "main-b", Measure.WEIGHT, Scope.ITEM, "200", "g", "goodsName", "200g");
        assertThat(errors(r, proposal(r, List.of(a, b), "개당 중량", "360g", Calculation.Operation.SUM, "g", "a", "b")))
                .anyMatch(e -> e.startsWith("QUANTITY_MIXED_ITEMS"));
        assertThat(errors(r, proposal(r, List.of(a, b), "개당 중량", "160g", Calculation.Operation.DIRECT, "g", "a")))
                .anyMatch(e -> e.startsWith("QUANTITY_MIXED_ITEMS"));
        r = request(List.of("개당 중량"), "160g + 사은품 200g", "160g + 사은품 200g");
        var gift = new Fact(b.id(), b.optionId(), b.componentId(), Role.EXTRA, b.measure(), b.scope(), b.amount(), b.unit(), b.source(), "사은품 200g");
        assertThat(errors(r, proposal(r, List.of(a, gift), "개당 중량", "160g", Calculation.Operation.DIRECT, "g", "a"))).isEmpty();
    }
    @Test void rejectsInventedQuoteNumberWrongSourceAndMissingSourceReview() {
        var r = request(List.of("수량"), "4팩", "6팩");
        for (var f : List.of(fact("a", "main", Measure.COUNT, Scope.TOTAL, "4", "팩", "productNoticeText", "4팩"),
                fact("a", "main", Measure.COUNT, Scope.TOTAL, "6", "팩", "goodsName", "4팩"),
                fact("a", "main", Measure.COUNT, Scope.TOTAL, "4", "팩", "goodsName", "4팩과6팩")))
            assertThat(errors(r, proposal(r, List.of(f), "수량", "4개", Calculation.Operation.DIRECT, "개", "a"))).isNotEmpty();
        var p = proposal(r, List.of(fact("a", "main", Measure.COUNT, Scope.TOTAL, "4", "팩", "goodsName", "4팩")),
                "수량", "4개", Calculation.Operation.DIRECT, "개", "a");
        p = new MappingProposal(true, .95, p.mappings(), p.reason(), new QuantityEvidence(p.quantityEvidence().facts(), p.quantityEvidence().bindings(), List.of()));
        assertThat(errors(r, p)).anyMatch(e -> e.startsWith("QUANTITY_SOURCE_COVERAGE"));
    }
    @Test void acceptsExplicitSingleSetWithoutFabricatingNumericQuote() {
        var r = request(List.of("수량"), "칫솔 세트", "칫솔 세트");
        var fact = fact("a", "main", Measure.COUNT, Scope.TOTAL, null, "세트", "goodsName", "칫솔 세트");
        assertThat(errors(r, proposal(r, List.of(fact), "수량", "1세트", Calculation.Operation.PACK_COUNT, "세트", "a"))).isEmpty();
    }

    @Test void rejectsCountUnitSpoofingNegativeClaimsAndCrossOptionEvidence() {
        for (String quote : List.of("70ml", "70g", "-70개")) {
            var r = request(List.of("수량"), quote, quote);
            var f = fact("a", "main", Measure.COUNT, Scope.TOTAL, "70", "개", "goodsName", quote);
            assertThat(errors(r, proposal(r, List.of(f), "수량", "70개", Calculation.Operation.DIRECT, "개", "a")))
                    .anyMatch(e -> e.startsWith("QUANTITY_EVIDENCE_INVALID"));
        }
        var r = new InferenceRequest("cross-option", "상품", null, List.of("수량"),
                List.of(new SourceOption("1", "4팩"), new SourceOption("2", "6팩")), "상품 정보");
        var f = fact("a", "main", Measure.COUNT, Scope.TOTAL, "6", "팩", "optionName1", "6팩");
        assertThat(errors(r, proposal(r, List.of(f), "수량", "6개", Calculation.Operation.DIRECT, "개", "a")))
                .anyMatch(e -> e.startsWith("QUANTITY_SOURCE_MISMATCH"));
    }

    @Test void rejectsDanglingLinksDuplicateFactsAndScopeSubstitution() {
        var r = request(List.of("수량"), "4팩", "4팩");
        var f = fact("a", "main", Measure.COUNT, Scope.TOTAL, "4", "팩", "goodsName", "4팩");
        var p = proposal(r, List.of(f), "수량", "4개", Calculation.Operation.DIRECT, "개", "a");
        for (var b : List.of(new Binding("1", "수량", "main", Scope.TOTAL, List.of("missing")),
                new Binding("1", "수량", "main", Scope.PACK, List.of("a")),
                new Binding("1", "수량", "main", Scope.TOTAL, List.of("a", "a")))) {
            var changed = new MappingProposal(true, .95, p.mappings(), p.reason(), context(r, List.of(f), List.of(b)));
            assertThat(errors(r, changed)).isNotEmpty();
        }
        assertThat(errors(r, new MappingProposal(true, .95, p.mappings(), p.reason(), context(r, List.of(f, f), p.quantityEvidence().bindings()))))
                .anyMatch(e -> e.startsWith("QUANTITY_EVIDENCE_INVALID"));
    }

    @Test void acceptsTotalCountAcrossComponentsButRejectsDuplicateObservationSum() {
        var r = request(List.of("수량"), "A 15팩 B 3팩", "A 15팩 B 3팩");
        var facts = List.of(fact("a", "A", Measure.COUNT, Scope.TOTAL, "15", "팩", "goodsName", "A 15팩"),
                fact("b", "B", Measure.COUNT, Scope.TOTAL, "3", "팩", "goodsName", "B 3팩"));
        var p = proposal(r, facts, "수량", "18개", Calculation.Operation.SUM, "개", "a", "b");
        var context = context(r, facts, List.of(new Binding("1", "수량", "*", Scope.TOTAL, List.of("a", "b"))));
        assertThat(errors(r, new MappingProposal(true, .95, p.mappings(), p.reason(), context))).isEmpty();
        var duplicate = fact("b", "A", Measure.COUNT, Scope.TOTAL, "15", "팩", "productCompositionText", "A 15팩");
        assertThat(errors(r, proposal(r, List.of(facts.get(0), duplicate), "수량", "30개", Calculation.Operation.SUM, "개", "a", "b")))
                .anyMatch(e -> e.startsWith("QUANTITY_OPERAND_MISMATCH"));
    }

    @Test void rejectsMalformedContextsWithoutThrowingAndAllowsCommaNumbers() {
        var r = request(List.of("개당 중량"), "1,000g", "1,000g");
        var f = fact("a", "main", Measure.WEIGHT, Scope.ITEM, "1,000", "g", "goodsName", "1,000g");
        var p = proposal(r, List.of(f), "개당 중량", "1kg", Calculation.Operation.CONVERT, "kg", "a");
        assertThat(errors(r, p)).isEmpty();
        var broken = new QuantityEvidence(Arrays.asList((Fact) null), Arrays.asList((Binding) null), Arrays.asList((SourceReview) null));
        assertThat(errors(r, new MappingProposal(true, .95, p.mappings(), p.reason(), broken))).isNotEmpty();
    }

    @Test void fullRejectionPreservesContextWithoutAdditionalAiCall() throws Exception {
        var r = request(List.of("수량"), "4팩", "3팩");
        var facts = List.of(fact("a", "main", Measure.COUNT, Scope.TOTAL, "4", "팩", "goodsName", "4팩"),
                fact("b", "main", Measure.COUNT, Scope.TOTAL, "3", "팩", "productCompositionText", "3팩"));
        var p = proposal(r, facts, "수량", "3개", Calculation.Operation.PACK_COUNT, "개", "b");
        var model = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var generation = new org.springframework.ai.chat.model.Generation(
                new org.springframework.ai.chat.messages.AssistantMessage(new ObjectMapper().writeValueAsString(p)),
                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build());
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(List.of(generation)));
        var result = new PurchaseOptionAiService(model, new ObjectMapper()).infer(r);
        assertThat(result.certain()).isFalse();
        assertThat(result.quantityEvidence()).isEqualTo(p.quantityEvidence());
        assertThat(result.reason()).contains("QUANTITY_SOURCE_CONFLICT");
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(1)).call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class));
    }
}
