package com.cware.ai.inference;

import com.cware.ai.dto.PurchaseOptionUnit;
import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.SourceOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.regex.Pattern;

/** 숫자와 요청 단위의 조합 형식만 검사한다. 원문의 의미 판단은 AI가 담당한다. */
public final class UnitSelection {
    // 형식 전용 패턴이다. 용량·중량의 소수를 허용해야 하므로 0과 선행 0도 형식상으로는 통과한다.
    // 수치의 타당성(양수 여부)은 positiveAmount가 담당하며 ResultValidator가 함께 호출한다.
    // 포장 개수처럼 1 이상의 정수만 허용하는 자리에는 CountUnitNormalizer의 더 엄격한 패턴을 쓴다.
    private static final String NUMBER = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?";
    private UnitSelection() {}

    public static String selectedUnit(String value, PurchaseOptionUnit units) {
        // 긴 단위부터 비교해 접미사가 겹치는 단위도 숫자+단위 전체 형식으로 확인한다.
        return Stream.concat(units.unitOptions().stream(), Stream.of(units.defaultUnit()))
                .distinct().sorted(Comparator.comparingInt(String::length).reversed())
                .filter(unit -> value.matches(NUMBER + Pattern.quote(unit))).findFirst().orElse(null);
    }

    /**
     * selectedUnit이 통과시킨 값의 수치가 0보다 큰지 확인한다.
     * NUMBER는 형식만 보므로 '0개'·'0.0ml'도 형식상으로는 올바르다. 용량·중량은 소수가 정당하므로
     * 정수 여부는 보지 않고 양수인지만 확인한다. 값의 의미 판단은 AI가 담당한다.
     */
    public static boolean positiveAmount(String value, String selectedUnit) {
        if (value == null || selectedUnit == null || value.length() <= selectedUnit.length()) return false;
        String number = value.substring(0, value.length() - selectedUnit.length()).replace(",", "");
        try {
            return new java.math.BigDecimal(number).signum() > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 테스트 모드는 단품의 단순 숫자/단위 또는 단일상품의 명확한 수량 문구만 처리한다. */
    public static MappingProposal.Entry mockEntry(InferenceRequest request, SourceOption option, PurchaseOptionUnit units) {
        if (QuantityContext.eligible(request)) {
            var entry = QuantityContext.mockEntry(request, units.purchaseOptionName());
            if (entry != null) {
                var measure = Pattern.compile("(" + NUMBER + ")(.+)").matcher(entry.value());
                if (measure.matches()) {
                    String outputUnit = units.unitOptions().contains(measure.group(2))
                            ? measure.group(2) : units.defaultUnit();
                    return new MappingProposal.Entry(entry.optionId(), entry.targetPurchaseOptionName(),
                            measure.group(1) + outputUnit, entry.confidence(), entry.evidenceSource(), entry.evidenceText());
                }
            }
        }
        // 숫자만 있으면 기본단위를 붙이고, 원문 단위가 선택지에 있으면 그 단위를 유지한다.
        String text = option.optionName1().strip();
        String value = null;
        if (text.matches(NUMBER)) value = text + units.defaultUnit();
        else if (text.matches(NUMBER + "\\s*[\\p{L}]+")) {
            var measure = Pattern.compile("(" + NUMBER + ")\\s*([\\p{L}]+)").matcher(text);
            if (measure.matches()) value = measure.group(1)
                    + (units.unitOptions().contains(measure.group(2)) ? measure.group(2) : units.defaultUnit());
        }
        else {
            var choices = units.unitOptions().stream().sorted(Comparator.comparingInt(String::length).reversed())
                    .map(Pattern::quote).toList();
            var pattern = Pattern.compile("(?<![\\d.,-])(" + NUMBER + ")\\s*(" + String.join("|", choices) + ")(?![\\p{L}\\d])");
            String source = text;
            if (text.equals("단일상품") && request.options().size() == 1 && units.purchaseOptionName().equals("수량"))
                source = request.goodsName() + "\n" + (request.productNoticeText() == null ? "" : request.productNoticeText());
            var matcher = pattern.matcher(source);
            // 후보 값이 둘 이상이고 서로 다르면 하나를 임의로 선택하지 않는다.
            while (matcher.find()) {
                String candidate = matcher.group(1) + matcher.group(2);
                if (value != null && !value.equals(candidate)) return null;
                value = candidate;
            }
        }
        return value == null ? null : new MappingProposal.Entry(option.optionId(), units.purchaseOptionName(), value, 0.0);
    }
}
