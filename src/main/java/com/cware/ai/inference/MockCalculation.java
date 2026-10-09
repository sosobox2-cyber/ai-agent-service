package com.cware.ai.inference;

import com.cware.ai.dto.Calculation;
import java.math.BigDecimal;
import java.util.List;

/** 모의 추출 결과에 화면에서 확인할 계산 설명 구조를 붙인다. */
final class MockCalculation {
    private MockCalculation() {}
    static MappingProposal.Entry attach(MappingProposal.Entry entry) {
        if (entry.calculation() != null) return entry;
        if (entry.evidenceSource() == null || entry.evidenceText() == null) return entry;
        var context = new Calculation.Evidence(entry.evidenceSource(), entry.evidenceText());
        var all = CalculationValidator.operandsFrom(context.source(), context.text());
        var output = CalculationValidator.operandsFrom(context.source(), entry.value());
        if (output.size() != 1) return entry;
        var result = output.get(0);
        Calculation.Operation operation;
        List<Calculation.Operand> operands;
        if (entry.value().equals("1세트")) {
            operation = Calculation.Operation.PACK_COUNT;
            operands = List.of();
        } else if (entry.targetPurchaseOptionName().equals("개당 중량") && all.size() >= 2) {
            operation = Calculation.Operation.SUM;
            operands = all;
        } else {
            operation = entry.targetPurchaseOptionName().equals("개당 수량")
                    ? Calculation.Operation.PACK_CONTENT : Calculation.Operation.DIRECT;
            operands = all.stream().filter(o -> new BigDecimal(o.amount().replace(",", "")).compareTo(
                    new BigDecimal(result.amount().replace(",", ""))) == 0).limit(1).toList();
            if (operands.isEmpty()) return entry;
            if (operation == Calculation.Operation.DIRECT) context = null;
        }
        return new MappingProposal.Entry(entry.optionId(), entry.targetPurchaseOptionName(), entry.value(),
                entry.confidence(), entry.evidenceSource(), entry.evidenceText(),
                new Calculation(operation, result.unit(), operands, context));
    }
}
