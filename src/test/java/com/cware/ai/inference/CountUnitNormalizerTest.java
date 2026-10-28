package com.cware.ai.inference;

import com.cware.ai.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 판매 용기 단위만 정규화하고 불확실 판정·원문 근거·허용 단위·불일치 피연산자는 보존하는지 검증한다. */
class CountUnitNormalizerTest {
    private InferenceRequest request() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                java.nio.file.Files.readString(java.nio.file.Path.of("examples/kitchen-towel-request.json")),
                InferenceRequest.class);
    }
    private MappingProposal proposal(String target, String unit, String operandAmount) {
        return new MappingProposal(false, .9, List.of(new MappingProposal.Entry("1", target, "12" + unit,
                .9, "goodsName", "12" + unit, new Calculation(Calculation.Operation.DIRECT, unit,
                List.of(new Calculation.Operand(operandAmount, unit, new Calculation.Evidence("goodsName", "12" + unit))),
                null))), "원문 수량");
    }
    @Test void preservesUncertaintyAndOriginalEvidence() throws Exception {
        var result = CountUnitNormalizer.normalize(request(), proposal("수량", "롤", "12"));
        assertThat(result.certain()).isFalse();
        assertThat(result.mappings().get(0).value()).isEqualTo("12개");
        assertThat(result.mappings().get(0).calculation().operands().get(0).unit()).isEqualTo("롤");
    }
    @Test void leavesMeasurementsAllowedUnitsAndInconsistentOperandsUnchanged() throws Exception {
        for (var input : List.of(proposal("수량", "g", "12"), proposal("개당 수량", "롤", "12"),
                proposal("수량", "롤", "4"), proposal("수량", "개", "12"))) {
            assertThat(CountUnitNormalizer.normalize(request(), input)).isEqualTo(input);
        }
    }

    @Test void restoresExplicitAllowedCountUnitWithoutChangingEvidence() {
        var request = new InferenceRequest("box", "30정 x 2박스", null, List.of("수량", "개당 캡슐/정"),
                List.of(new SourceOption("1", "단일상품")), "30정 x 2박스",
                List.of(new PurchaseOptionUnit("수량", "개", List.of("개", "박스")),
                        new PurchaseOptionUnit("개당 캡슐/정", "개", List.of("개", "정"))));
        var mappings = List.of(countEntry("수량", "2", "박스", Calculation.Operation.PACK_COUNT),
                countEntry("개당 캡슐/정", "30", "정", Calculation.Operation.PACK_CONTENT));
        var proposal = new MappingProposal(true, .9, mappings, "원문 단위");
        var normalized = CountUnitNormalizer.normalize(request, proposal);
        assertThat(normalized.mappings()).extracting(MappingProposal.Entry::value).containsExactly("2박스", "30정");
        assertThat(normalized.mappings()).extracting(e -> e.calculation().outputUnit()).containsExactly("박스", "정");
        assertThat(normalized.mappings()).extracting(e -> e.calculation().operands().get(0).unit()).containsExactly("박스", "정");
        assertThat(new ResultValidator().validateProposal(request, normalized)).isEmpty();
    }

    @Test void normalizesOnlyConfiguredPhysicalUnitCase() {
        var request = new InferenceRequest("paper", "250M", null, List.of("길이"),
                List.of(new SourceOption("1", "단일상품")), "250M",
                List.of(new PurchaseOptionUnit("길이", "m", List.of("m", "cm"))));
        var entry = new MappingProposal.Entry("1", "길이", "250M", .9, "goodsName", "250M");
        var proposal = new MappingProposal(true, .9, List.of(entry), "원문 길이");
        var normalized = CountUnitNormalizer.normalize(request, proposal);
        assertThat(normalized.mappings().get(0).value()).isEqualTo("250m");
        assertThat(new ResultValidator().validateProposal(request, normalized)).isEmpty();
        assertThat(normalized.mappings().get(0).evidenceText()).isEqualTo("250M");
    }

    private MappingProposal.Entry countEntry(String target, String amount, String originalUnit, Calculation.Operation operation) {
        return new MappingProposal.Entry("1", target, amount + "개", .9, "goodsName", amount + originalUnit,
                new Calculation(operation, "개", List.of(new Calculation.Operand(amount, originalUnit,
                        new Calculation.Evidence("goodsName", amount + originalUnit))), null));
    }
}
