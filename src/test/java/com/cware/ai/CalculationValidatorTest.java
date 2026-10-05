package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.cware.ai.dto.Calculation.Operation.*;

class CalculationValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private InferenceRequest example(String name) throws Exception {
        return mapper.readValue(Files.readString(Path.of("examples", name + "-request.json")), InferenceRequest.class);
    }
    private Calculation.Evidence evidence(String source, String text) { return new Calculation.Evidence(source, text); }
    private Calculation.Operand operand(String amount, String unit, String source, String text) {
        return new Calculation.Operand(amount, unit, evidence(source, text));
    }
    private MappingProposal.Entry entry(String target, String value, Calculation.Operation op, String unit,
            Calculation.Evidence context, Calculation.Operand... operands) {
        return new MappingProposal.Entry("1", target, value, .90, null, null,
                new Calculation(op, unit, List.of(operands), context));
    }
    private InferenceRequest named(InferenceRequest r, String name) {
        return new InferenceRequest(r.goodsId(), name, r.brand(), r.categoryName(), r.coupangCategoryId(),
                r.coupangCategoryName(), r.allowedPurchaseOptions(), r.options(), r.productNoticeText());
    }

    @Test void directExtractionNormalizesWhitespaceAndTrailingZeroAndConversionWorks() throws Exception {
        var r = example("capacity");
        var direct = entry("개당 용량", "50.0 ML", DIRECT, "ml", null,
                operand("50", "ml", "goodsName", "선크림  50 ml"));
        assertThat(CalculationValidator.evaluate(r, direct).value()).isEqualTo("50ml");
        var converted = entry("개당 용량", "0.05L", CONVERT, "L", null,
                operand("50", "ml", "productNoticeText", "선크림 50ml"));
        assertThat(CalculationValidator.evaluate(r, converted).value()).isEqualTo("0.05L");
    }

    @Test void servicePreservesAiEvidenceWithoutRepairingSource() throws Exception {
        var r = example("set");
        var context = evidence("goodsName", "칫솔 10개세트");
        var quantity = entry("수량", "1세트", PACK_COUNT, "세트", context);
        var contents = entry("개당 수량", "10매입", PACK_CONTENT, "매입", context,
                operand("10", "개", "goodsName", "10개"));
        var proposal = new MappingProposal(true, .90, List.of(quantity, contents), "칫솔 10개 한 세트입니다.");
        var service = new PurchaseOptionInferenceService(new RequestValidator(), ignored -> proposal,
                new ResultValidator(), new InputHashService(mapper), Fixtures.properties());
        var result = service.infer(r);
        assertThat(result.success()).isTrue();
        assertThat(result.items().get(0).purchaseOptions()).containsEntry("수량", "1세트").containsEntry("개당 수량", "10매입");
        assertThat(result.aiAssessment().mappings().get(1).calculation().context().source()).isEqualTo("goodsName");
        assertThat(result.optionMappings().get(1).calculation()).isEqualTo(contents.calculation());
        assertThat(result.optionMappings().get(1).calculation().context().source()).isEqualTo("goodsName");
    }

    @Test void sumsMixedMassUnitsWithExactArithmeticAndRequiresAllComponents() throws Exception {
        var r = named(example("weight"), "김치 4200g + 파김치 1kg");
        var context = evidence("goodsName", r.goodsName());
        var good = entry("개당 중량", "5.2kg", SUM, "kg", context,
                operand("4200", "g", "goodsName", "김치 4200g"), operand("1", "kg", "goodsName", "파김치 1kg"));
        assertThat(CalculationValidator.evaluate(r, good).value()).isEqualTo("5.2kg");
        var bad = entry("개당 중량", "6.2kg", SUM, "kg", context,
                operand("4200", "g", "goodsName", "김치 4200g"), operand("1", "kg", "goodsName", "파김치 1kg"));
        assertThat(CalculationValidator.evaluate(r, bad).error()).contains("재계산");
        var three = named(r, "김치 4200g + 파김치 1kg + 열무김치 1kg");
        var omitted = entry("개당 중량", "5.2kg", SUM, "kg", evidence("goodsName", three.goodsName()),
                operand("4200", "g", "goodsName", "김치 4200g"), operand("1", "kg", "goodsName", "파김치 1kg"));
        assertThat(CalculationValidator.evaluate(three, omitted).error()).contains("누락·중복");
    }

    @Test void rejectsInventedMeasurementsCrossDimensionGiftsAndUnknownOperations() throws Exception {
        var r = example("capacity");
        for (var bad : List.of(
                entry("개당 용량", "60ml", DIRECT, "ml", null, operand("60", "ml", "goodsName", "50ml")),
                entry("개당 중량", "50g", CONVERT, "g", null, operand("50", "ml", "goodsName", "50ml")),
                entry("개당 용량", "50ml", DIRECT, "ml", null, operand("50", "ml", "goodsName", "없는 문구 50ml")),
                entry("개당 용량", "50ml", DIRECT, "ml", null, operand("50", "oz", "goodsName", "50ml")))) {
            assertThat(CalculationValidator.evaluate(r, bad).valid()).isFalse();
        }
        var gift = example("quantity");
        var badGift = entry("개당 수량", "10개입", PACK_CONTENT, "개입", evidence("productNoticeText", "무료체험분 1매(10패치)"),
                operand("10", "패치", "productNoticeText", "10패치"));
        assertThat(CalculationValidator.evaluate(gift, badGift).valid()).isFalse();
        var noOperation = new MappingProposal.Entry("1", "수량", "7개", .9, null, null,
                new Calculation(null, "개", List.of(), null));
        assertThat(CalculationValidator.evaluate(r, noOperation).valid()).isFalse();
    }

    @Test void doesNotApplyCommonProductEvidenceToMultipleVariants() throws Exception {
        var r = Fixtures.withOptions(example("capacity"), List.of(new SourceOption("1", "50ml 7개"), new SourceOption("2", "50ml 9개")));
        var shared = entry("수량", "7개", DIRECT, "개", null, operand("7", "개", "goodsName", "선크림 50ml 7개"));
        assertThat(CalculationValidator.evaluate(r, shared).valid()).isFalse();
        var individual = entry("수량", "7개", DIRECT, "개", null, operand("7", "개", "optionName1", "7개"));
        assertThat(CalculationValidator.evaluate(r, individual).value()).isEqualTo("7개");
    }

    @Test void supportsIntegerPackCountsAndRejectsFractionalCountsAndFalseOneSet() throws Exception {
        var r = named(example("set"), "칫솔 10개입 2세트");
        var context = evidence("goodsName", r.goodsName());
        var good = entry("수량", "2세트", PACK_COUNT, "세트", context, operand("2", "세트", "goodsName", "2세트"));
        assertThat(CalculationValidator.evaluate(r, good).value()).isEqualTo("2세트");
        assertThat(CalculationValidator.evaluate(r, entry("수량", "1세트", PACK_COUNT, "세트", context)).valid()).isFalse();
        var fraction = named(r, "칫솔 1.5개");
        assertThat(CalculationValidator.evaluate(fraction,
                entry("수량", "1.5개", DIRECT, "개", null, operand("1.5", "개", "goodsName", "1.5개"))).valid()).isFalse();
        assertThat(CalculationValidator.evaluate(r,
                entry("개당 수량", "10개입", DIRECT, "개입", null, operand("10", "개입", "goodsName", "10개입"))).valid()).isTrue();
        var totalOnly = named(r, "칫솔 10개");
        assertThat(CalculationValidator.evaluate(totalOnly,
                entry("개당 수량", "10개입", DIRECT, "개입", null, operand("10", "개", "goodsName", "10개"))).valid()).isFalse();
    }

    @Test void allExistingQuantityExamplesUseCommonOperationsInTestMode() throws Exception {
        for (String name : List.of("quantity", "capacity", "weight", "set")) {
            var r = example(name);
            var proposal = MockOptionInferenceService.infer(r);
            assertThat(proposal.mappings()).isNotEmpty().allSatisfy(mapping -> {
                assertThat(mapping.calculation()).as(name).isNotNull();
                assertThat(CalculationValidator.evaluate(r, mapping).valid()).as(name).isTrue();
            });
            assertThat(new ResultValidator().validateProposal(r, proposal)).isEmpty();
        }
    }

    @Test void numericEvidenceCannotBePartOfLargerOrNegativeNumberOrStripPackSuffix() throws Exception {
        var r = example("capacity");
        for (String title : List.of("용량 150ml", "용량 -50ml", "용량 1.50ml")) {
            assertThat(CalculationValidator.evaluate(Fixtures.withNotice(named(r, title), null),
                    entry("개당 용량", "50ml", DIRECT, "ml", null,
                            operand("50", "ml", "goodsName", "50ml"))).valid()).as(title).isFalse();
        }
        var counts = named(r, "10개입");
        assertThat(CalculationValidator.evaluate(counts,
                entry("수량", "10개", DIRECT, "개", null, operand("10", "개", "goodsName", "10개"))).valid()).isFalse();
    }

    @Test void contextCannotHideExplicitMultiplePackSales() throws Exception {
        var r = named(example("set"), "칫솔 10개입 2세트");
        assertThat(CalculationValidator.evaluate(r, entry("수량", "1세트", PACK_COUNT, "세트",
                evidence("productNoticeText", "10개 세트"))).valid()).isFalse();
    }

    @Test void numericProofDeterminesOriginalUnitWhenAiUsesOutputUnitForOperand() throws Exception {
        var r = example("quantity");
        var quantity = entry("수량", "9개", DIRECT, "개", null, operand("9", "개", "goodsName", "9박스"));
        var result = CalculationValidator.evaluate(r, quantity);
        assertThat(result.value()).isEqualTo("9개");
        assertThat(result.calculation().operands().get(0).unit()).isEqualTo("박스");
        var contents = entry("개당 수량", "50개입", PACK_CONTENT, "개입",
                evidence("productNoticeText", "1박스: 5매, 총 50패치"), operand("50", "개", "productNoticeText", "50패치"));
        assertThat(CalculationValidator.evaluate(r, contents).calculation().operands().get(0).unit()).isEqualTo("패치");
        var capacity = example("capacity");
        var badDimension = entry("개당 중량", "50g", DIRECT, "g", null, operand("50", "g", "goodsName", "50ml"));
        assertThat(CalculationValidator.evaluate(capacity, badDimension).valid()).isFalse();
    }

    @Test void canonicalPackContentUnitUsesPiecesOrSheetsWithoutChangingNumber() throws Exception {
        var r = example("quantity");
        var pieces = entry("개당 수량", "50매입", PACK_CONTENT, "매입",
                evidence("productNoticeText", "1박스: 5매, 총 50패치"), operand("50", "개", "productNoticeText", "50패치"));
        assertThat(CalculationValidator.evaluate(r, pieces).value()).isEqualTo("50개입");
        assertThat(CalculationValidator.evaluate(r, pieces).calculation().outputUnit()).isEqualTo("개입");
        var wrongNumber = entry("개당 수량", "49매입", PACK_CONTENT, "매입",
                evidence("productNoticeText", "1박스: 5매, 총 50패치"), operand("50", "개", "productNoticeText", "50패치"));
        assertThat(CalculationValidator.evaluate(r, wrongNumber).valid()).isFalse();
        var sheets = named(Fixtures.withNotice(r, null), "시트 10매 세트");
        var sheetEntry = entry("개당 수량", "10개입", PACK_CONTENT, "개입",
                evidence("goodsName", sheets.goodsName()), operand("10", "매", "goodsName", "10매"));
        assertThat(CalculationValidator.evaluate(sheets, sheetEntry).value()).isEqualTo("10매입");
    }
}
