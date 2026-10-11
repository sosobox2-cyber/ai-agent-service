package com.cware.ai.inference;

import com.cware.ai.dto.Calculation;
import com.cware.ai.dto.InferenceRequest;
import java.util.Set;
import java.util.regex.Pattern;

/** 판매 수량의 용기 단위를 기본 개수 단위로 표시하며 원문 피연산자는 보존한다. */
final class CountUnitNormalizer {
    private static final Pattern COUNT = Pattern.compile("([1-9]\\d{0,5})(롤|통|병|팩|봉|튜브)");
    private static final Set<Calculation.Operation> OPERATIONS = Set.of(
            Calculation.Operation.DIRECT, Calculation.Operation.PACK_COUNT);

    static MappingProposal normalize(InferenceRequest request, MappingProposal proposal) {
        if (proposal == null || proposal.mappings() == null) return proposal;
        // 수량의 기본단위를 '개'로 설정한 요청에만 용기 단위 표시 정규화를 적용한다.
        var units = request.purchaseOptionUnits().stream()
                .filter(u -> "수량".equals(u.purchaseOptionName()) && "개".equals(u.defaultUnit()))
                .findFirst().orElse(null);
        if (units == null) return proposal;
        var entries = proposal.mappings().stream().map(entry -> {
            if (entry == null || !"수량".equals(entry.targetPurchaseOptionName()) || entry.value() == null
                    || UnitSelection.selectedUnit(entry.value(), units) != null) return entry;
            var match = COUNT.matcher(entry.value());
            if (!match.matches()) return entry;
            // 값·계산 출력·단일 피연산자의 숫자와 단위가 일치한 직접 추출 또는 포장 수만 바꾼다.
            var calculation = entry.calculation();
            if (calculation == null || !OPERATIONS.contains(calculation.operation())
                    || !match.group(2).equals(calculation.outputUnit()) || calculation.operands() == null
                    || calculation.operands().size() != 1) return entry;
            var operand = calculation.operands().get(0);
            if (operand == null || !match.group(1).equals(operand.amount())
                    || !match.group(2).equals(operand.unit())) return entry;
            // 숫자와 원문 근거는 유지하고 반환 표시와 계산 출력 단위만 '개'로 맞춘다.
            return new MappingProposal.Entry(entry.optionId(), entry.targetPurchaseOptionName(),
                    match.group(1) + "개", entry.confidence(), entry.evidenceSource(), entry.evidenceText(),
                    new Calculation(Calculation.Operation.PACK_COUNT, "개", calculation.operands(), calculation.context()));
        }).toList();
        return new MappingProposal(proposal.certain(), proposal.confidence(), entries, proposal.reason());
    }
}
