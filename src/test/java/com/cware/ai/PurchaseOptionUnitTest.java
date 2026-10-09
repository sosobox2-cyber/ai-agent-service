package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PurchaseOptionUnitTest {
    @Test void noneDefaultIsEquivalentToNoUnitSettingEvenWithChoices() {
        var hashes = new InputHashService(new ObjectMapper());
        for (String defaultUnit : List.of("없음", " 없음 ")) {
            for (List<String> choices : List.of(List.<String>of(), List.of("개", "박스"))) {
                var normalized = request(List.of(new PurchaseOptionUnit("수량", defaultUnit, choices)), "6병");
                assertThat(normalized.purchaseOptionUnits()).isEmpty();
                new RequestValidator().validate(normalized);
                try (var factory = Validation.buildDefaultValidatorFactory()) {
                    assertThat(factory.getValidator().validate(normalized)).isEmpty();
                }
                assertThat(hashes.hash(normalized)).isEqualTo(hashes.hash(request(List.of(), "6병")));
                assertThat(new ResultValidator().validateProposal(normalized, proposal("6병", null))).isEmpty();
            }
        }
    }
    private final PurchaseOptionUnit units = new PurchaseOptionUnit("수량", "개", List.of("개", "박스", "세트"));
    private InferenceRequest request(List<PurchaseOptionUnit> settings, String source) {
        return new InferenceRequest("unit-test", "테스트 상품", "테스트",
                List.of("수량"), List.of(new SourceOption("1", source)), "제조국: 한국", settings);
    }

    @Test void mockUsesExplicitUnitAndFallsBackOnlyForBareNumber() {
        for (String source : List.of("6박스", "6세트", "6개", "6")) {
            var result = MockOptionInferenceService.infer(request(List.of(units), source));
            assertThat(result.mappings()).hasSize(1);
            assertThat(result.mappings().get(0).value()).isEqualTo(source.equals("6") ? "6개" : source);
        }
        assertThat(MockOptionInferenceService.infer(request(List.of(units), "6병")).mappings().get(0).value()).isEqualTo("6개");
        for (String source : List.of("모름", "6박스 / 7세트"))
            assertThat(MockOptionInferenceService.infer(request(List.of(units), source)).mappings()).isEmpty();
    }

    @Test void serverRejectsUnsupportedOrMissingUnitAndMismatchedCalculation() {
        var validator = new ResultValidator();
        var r = request(List.of(units), "6박스");
        for (String value : List.of("6박스", "6세트", "6개"))
            assertThat(validator.validateProposal(r, proposal(value, null))).isEmpty();
        for (String value : List.of("6", "6병", "여섯박스", "박스", "6박스개"))
            assertThat(validator.validateProposal(r, proposal(value, null))).isNotEmpty();
        assertThat(validator.validateProposal(r, proposal("6박스",
                new Calculation(Calculation.Operation.DIRECT, "개", List.of(), null)))).isNotEmpty();
    }

    @Test void mixedRequestOnlyRestrictsUnitsForConfiguredTarget() {
        var r = new InferenceRequest("mixed", "TV 109cm", "가전>TV",
                List.of("수량", "화면크기(cm)"), List.of(new SourceOption("1", "단품")), "제조국: 한국", List.of(units));
        var screen = new MappingProposal.Entry("1", "화면크기(cm)", "109cm", .95, "goodsName", "109cm",
                new Calculation(Calculation.Operation.DIRECT, "cm", List.of(
                        new Calculation.Operand("109", "cm", new Calculation.Evidence("goodsName", "109cm"))), null));
        var validator = new ResultValidator();
        var valid = new MappingProposal(true, .95, List.of(screen,
                new MappingProposal.Entry("1", "수량", "1개", .95)), "화면크기와 수량 추출");
        assertThat(validator.validateProposal(r, valid)).isEmpty();
        var invalid = new MappingProposal(true, .95, List.of(screen,
                new MappingProposal.Entry("1", "수량", "1대", .95)), "화면크기와 수량 추출");
        assertThat(validator.validateProposal(r, invalid))
                .containsExactly("단위가 설정된 구매옵션 값은 숫자와 허용된 단위를 조합해야 합니다.");
    }

    private MappingProposal proposal(String value, Calculation calculation) {
        return new MappingProposal(true, .9, List.of(new MappingProposal.Entry("1", "수량", value, .9,
                null, null, calculation)), "단위를 선택했습니다.");
    }

    @Test void validatesUnitRelationshipsBeforeInference() {
        var validator = new RequestValidator();
        validator.validate(request(List.of(units), "6"));
        for (var settings : List.of(
                List.of(new PurchaseOptionUnit("없는 옵션", "개", List.of("개"))),
                List.of(units, units),
                List.of(new PurchaseOptionUnit("수량", "개", List.of("개", "개"))),
                List.of(new PurchaseOptionUnit("수량", "개 ", List.of("개 ")))))
            assertThatThrownBy(() -> validator.validate(request(settings, "6"))).hasMessageContaining("입력");
    }

    @Test void beanValidationRejectsNullAndIncompleteSettings() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(request(Arrays.asList((PurchaseOptionUnit) null), "6"))).isNotEmpty();
            assertThat(validator.validate(request(List.of(new PurchaseOptionUnit("수량", "", List.of())), "6"))).isNotEmpty();
            assertThat(validator.validate(request(List.of(new PurchaseOptionUnit("수량", "개", Arrays.asList("개", null))), "6"))).isNotEmpty();
        }
    }

    @Test void defaultOutsideChoicesIsAcceptedAndUsedForFallback() {
        var setting = new PurchaseOptionUnit("수량", "개", List.of("박스", "세트"));
        var r = request(List.of(setting), "6병");
        new RequestValidator().validate(r);
        assertThat(new ResultValidator().validateProposal(r, proposal("6개", null))).isEmpty();
        assertThat(new ResultValidator().validateProposal(r, proposal("6병", null))).isNotEmpty();
        for (String source : List.of("6", "6병"))
            assertThat(MockOptionInferenceService.infer(request(List.of(setting), source)).mappings().get(0).value()).isEqualTo("6개");
        assertThat(MockOptionInferenceService.infer(request(List.of(setting), "6박스")).mappings().get(0).value()).isEqualTo("6박스");
        var schema = new ObjectMapper().valueToTree(PurchaseOptionAiService.schema(r));
        assertThat(schema.at("/properties/mappings/items/properties/calculation/anyOf/0/properties/outputUnit/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/mappings/items/properties/calculation/anyOf/0/properties/outputUnit").has("enum")).isFalse();
    }

    @Test void unitChangesAffectHashButOrderingDoesNot() {
        var hashes = new InputHashService(new ObjectMapper());
        String original = hashes.hash(request(List.of(units), "6"));
        assertThat(original).isEqualTo(hashes.hash(request(List.of(
                new PurchaseOptionUnit("수량", "개", List.of("세트", "박스", "개"))), "6")));
        assertThat(original).isNotEqualTo(hashes.hash(request(List.of(
                new PurchaseOptionUnit("수량", "박스", units.unitOptions())), "6")));
        assertThat(original).isNotEqualTo(hashes.hash(request(List.of(
                new PurchaseOptionUnit("수량", "개", List.of("개", "박스"))), "6")));
        assertThat(original).isNotEqualTo(hashes.hash(request(List.of(), "6")));
    }

    @Test void responseSchemaAcceptsCallerSuppliedUnit() {
        var mapper = new ObjectMapper();
        var schema = mapper.valueToTree(PurchaseOptionAiService.schema(request(List.of(
                new PurchaseOptionUnit("수량", "묶음", List.of("묶음"))), "6")));
        assertThat(schema.at("/properties/mappings/items/properties/calculation/anyOf/0/properties/outputUnit/type").asText())
                .isEqualTo("string");
        assertThat(schema.at("/properties/mappings/items/properties/calculation/anyOf/0/properties/outputUnit").has("enum")).isFalse();
        assertThat(schema.at("/properties/mappings/items/properties/calculation/anyOf/0/properties/operands/items/properties/unit/type").asText())
                .isEqualTo("string");
    }
}
