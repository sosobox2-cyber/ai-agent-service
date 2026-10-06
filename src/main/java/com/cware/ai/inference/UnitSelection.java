package com.cware.ai.inference;

import com.cware.ai.dto.PurchaseOptionUnit;
import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.SourceOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.regex.Pattern;

/** 숫자와 요청 단위의 조합 형식만 검사한다. 원문의 의미 판단은 AI가 담당한다. */
public final class UnitSelection {
    private static final String NUMBER = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?";
    private UnitSelection() {}

    public static String selectedUnit(String value, PurchaseOptionUnit units) {
        return Stream.concat(units.unitOptions().stream(), Stream.of(units.defaultUnit()))
                .distinct().sorted(Comparator.comparingInt(String::length).reversed())
                .filter(unit -> value.matches(NUMBER + Pattern.quote(unit))).findFirst().orElse(null);
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
            while (matcher.find()) {
                String candidate = matcher.group(1) + matcher.group(2);
                if (value != null && !value.equals(candidate)) return null;
                value = candidate;
            }
        }
        return value == null ? null : new MappingProposal.Entry(option.optionId(), units.purchaseOptionName(), value, 0.0);
    }
}
