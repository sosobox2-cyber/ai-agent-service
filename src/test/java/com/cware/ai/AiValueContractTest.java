package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 운영에서 발견한 오류를 익명화한 고정 기준. 등록 기준값과의 문자열 일치율을 정답률로 쓰지 않는다. */
class AiValueContractTest {
    static InferenceRequest request(String target, String unit) {
        return new InferenceRequest("contract-fixture", "평가 상품", null, List.of(target),
                List.of(new SourceOption("1", "단일상품")), "검증용 상품 정보",
                unit == null ? List.of() : List.of(new PurchaseOptionUnit(target, unit, List.of(unit))));
    }
    static MappingProposal proposal(String target, String value, String inputUnit, String outputUnit, String evidence) {
        return new MappingProposal(true, .95, List.of(new MappingProposal.Entry("1", target, value, .95,
                "productNoticeText", evidence, inputUnit == null ? null : new Calculation(
                Calculation.Operation.DIRECT, outputUnit, List.of(new Calculation.Operand("70", inputUnit,
                new Calculation.Evidence("productNoticeText", evidence))), null))), "고정 회귀 제안");
    }
    static InferenceResponse assess(InferenceRequest request, MappingProposal proposal) {
        return new PurchaseOptionInferenceService(new RequestValidator(), ignored -> proposal,
                new ResultValidator(), new InputHashService(new ObjectMapper()), Fixtures.properties()).infer(request);
    }

    @ParameterizedTest
    @CsvSource({"ml,개입", "g,개입", "L,개", "cm,매입", "kg,정"})
    void rejectsPhysicalQuantityAsCountAndPreservesProposal(String input, String output) {
        var proposal = proposal("개당 수량", "70" + output, input, output, "70" + input);
        var result = assess(request("개당 수량", output), proposal);
        assertThat(result.success()).isFalse();
        assertThat(result.autoApplyCandidate()).isFalse();
        assertThat(result.errorCode()).isEqualTo("RESULT_VALIDATION_FAILED");
        assertThat(result.optionMappings()).isEmpty();
        assertThat(result.items()).isEmpty();
        assertThat(result.aiAssessment().mappings().get(0).value()).isEqualTo("70" + output);
    }
    @ParameterizedTest
    @CsvSource({"병,개", "팩,개", "롤,개", "매,매입", "개,개입", "ml,ml", "g,g"})
    void preservesCountFallbackAndPhysicalUnits(String input, String output) {
        String target = input.equals("ml") ? "개당 용량" : input.equals("g") ? "개당 중량" : "수량";
        var original = request(target, output);
        var r = new InferenceRequest(original.goodsId(), original.goodsName(), null, original.allowedPurchaseOptions(),
                original.options(), "70" + input, original.purchaseOptionUnits());
        assertThat(assess(r, Fixtures.grounded(r, proposal(target, "70" + output, input, output, "70" + input))).success()).isTrue();
    }
    @ParameterizedTest
    @CsvSource({"PLUS,false", "PLUS8,false", "대형,false", "0명,false", "-2명,false", "0.5명,false",
            "2인용,true", "2~4명,true", "한 명,true", "두 명,true", "최대 8인,true", "6명 이상,true"})
    void requiresPersonCountWithoutAddingUnits(String value, boolean expected) {
        var result = assess(request("사용 인원", null), proposal("사용 인원", value, null, null, value));
        assertThat(result.success()).isEqualTo(expected);
        if (expected) assertThat(result.optionMappings().get(0).value()).isEqualTo(value);
    }
    @Test void rejectsExplicitCertificateEvidenceButNotOrdinaryModel() {
        assertThat(assess(request("모델명/품번", null), proposal("모델명/품번", "R-R-ABC-MODEL",
                null, null, "등록번호 R-R-ABC-MODEL")).success()).isFalse();
        assertThat(assess(request("모델명/품번", null), proposal("모델명/품번", "MODEL-27",
                null, null, "모델명 MODEL-27")).success()).isTrue();
    }
    @Test void keepsMockButActualQuantityRequiresEvidenceEvenWithoutUnitSettings() {
        var validator = new ResultValidator();
        var p = proposal("수량", "70개", "ml", "개", "70ml");
        assertThat(validator.validateProposal(request("수량", "개"), p)).isEmpty();
        assertThat(validator.validateAiProposal(request("수량", null), p))
                .anyMatch(e -> e.startsWith("QUANTITY_EVIDENCE_REQUIRED"));
        assertThat(assess(request("수량", "개"), proposal("수량", "12개", "병", "개", "70병")).success()).isFalse();
        assertThat(assess(Fixtures.request(), Fixtures.proposal()).success()).isTrue();
    }
}
