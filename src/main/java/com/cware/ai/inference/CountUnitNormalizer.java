package com.cware.ai.inference;

import com.cware.ai.dto.Calculation;
import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.PurchaseOptionUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 판매 수량의 용기 단위를 기본 개수 단위로 표시하며 원문 피연산자는 보존한다. */
final class CountUnitNormalizer {
    private static final Pattern COUNT = Pattern.compile("([1-9]\\d{0,5})(롤|통|병|팩|봉|튜브)");
    private static final Set<Calculation.Operation> OPERATIONS = Set.of(
            Calculation.Operation.DIRECT, Calculation.Operation.PACK_COUNT);
    private static final Set<Calculation.Operation> DISPLAY_OPERATIONS = Set.of(
            Calculation.Operation.DIRECT, Calculation.Operation.PACK_COUNT, Calculation.Operation.PACK_CONTENT);

    static MappingProposal normalize(InferenceRequest request, MappingProposal proposal) {
        if (proposal == null || proposal.mappings() == null) return proposal;
        var countUnits = request.purchaseOptionUnits().stream()
                .filter(u -> "수량".equals(u.purchaseOptionName()) && "개".equals(u.defaultUnit()))
                .findFirst().orElse(null);
        var entries = proposal.mappings().stream().map(entry -> {
            if (entry == null || entry.value() == null) return entry;
            var configured = request.purchaseOptionUnits().stream()
                    .filter(u -> u.purchaseOptionName().equals(entry.targetPurchaseOptionName()))
                    .findFirst().orElse(null);
            if (configured != null) {
                var exact = normalizeConfiguredUnit(entry, configured);
                if (exact != entry) return exact;
            }
            if (countUnits == null || !"수량".equals(entry.targetPurchaseOptionName())
                    || UnitSelection.selectedUnit(entry.value(), countUnits) != null) return entry;
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
                    new Calculation(Calculation.Operation.PACK_COUNT, "개", calculation.operands(), calculation.context()),
                    MappingProposal.UNIT_DISPLAY);
        }).toList();
        return new MappingProposal(proposal.certain(), proposal.confidence(), entries, proposal.reason(), proposal.quantityEvidence());
    }

    private static MappingProposal.Entry normalizeConfiguredUnit(MappingProposal.Entry entry, PurchaseOptionUnit units) {
        var calculation = entry.calculation();
        if (calculation != null && calculation.operation() == Calculation.Operation.DIRECT
                && calculation.operands() != null && calculation.operands().size() == 1) {
            var operand = calculation.operands().get(0);
            if (operand != null && operand.amount() != null && operand.unit() != null
                    && operand.unit().matches("(?i:mg|kg|g|ml|l|cc|mm|cm|m)")
                    && units.unitOptions().contains(operand.unit())
                    && units.defaultUnit().equals(calculation.outputUnit())
                    && !operand.unit().equals(calculation.outputUnit())
                    && entry.value().equals(operand.amount() + operand.unit()))
                return withOutput(entry, entry.value(), operand.unit());
        }
        if (calculation != null && calculation.operands() != null && calculation.operands().size() == 1
                && DISPLAY_OPERATIONS.contains(calculation.operation())) {
            var operand = calculation.operands().get(0);
            if (operand != null && operand.amount() != null && operand.unit() != null
                    && units.unitOptions().contains(operand.unit())
                    && QuantityEvidenceValidator.COUNTS.contains(operand.unit().toLowerCase(Locale.ROOT))
                    && !operand.unit().equals(calculation.outputUnit())
                    && units.defaultUnit().equals(calculation.outputUnit())
                    && entry.value().equals(operand.amount() + calculation.outputUnit()))
                return withOutput(entry, operand.amount() + operand.unit(), operand.unit());
        }
        if (UnitSelection.selectedUnit(entry.value(), units) != null) return entry;
        List<String> matches = units.unitOptions().stream()
                .filter(u -> u.matches("(?i:mg|kg|g|ml|l|cc|mm|cm|m)"))
                .filter(u -> entry.value().length() > u.length()
                        && entry.value().regionMatches(true, entry.value().length() - u.length(), u, 0, u.length()))
                .filter(u -> UnitSelection.positiveAmount(entry.value(), u))
                .filter(u -> calculation == null || calculation.outputUnit() != null
                        && calculation.outputUnit().equalsIgnoreCase(u))
                .toList();
        if (matches.size() != 1) return entry;
        String unit = matches.get(0);
        return withOutput(entry, entry.value().substring(0, entry.value().length() - unit.length()) + unit, unit);
    }

    private static MappingProposal.Entry withOutput(MappingProposal.Entry entry, String value, String unit) {
        var old = entry.calculation();
        boolean changed = !value.equals(entry.value()) || old != null && !unit.equals(old.outputUnit());
        var calculation = old == null ? null : new Calculation(old.operation(), unit, old.operands(), old.context());
        return new MappingProposal.Entry(entry.optionId(), entry.targetPurchaseOptionName(), value,
                entry.confidence(), entry.evidenceSource(), entry.evidenceText(), calculation,
                changed ? MappingProposal.UNIT_DISPLAY : entry.serverNormalization());
    }
}
