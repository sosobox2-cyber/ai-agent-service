package com.cware.ai.inference;

import com.cware.ai.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

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
}
