package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.SourceOption;
import java.util.*;
import java.util.regex.Pattern;

/** 원문에 실제로 있는 숫자·단위 문자열만 AI에 힌트로 제공한다. 승인 근거는 별도 검증한다. */
final class QuantitySourceHints {
    private QuantitySourceHints() {}
    private static final Pattern COUNTS = Pattern.compile("(?<![0-9])([1-9][0-9]{0,5})\\s*(매|팩|롤|박스|정|캡슐|포|병|개|세트)");
    private static final Pattern PHYSICAL = Pattern.compile("(?<![0-9A-Za-z])([1-9][0-9]{0,5}(?:\\.[0-9]{1,3})?)\\s*(kg|mg|g|ml|mL|L|cc)(?![A-Za-z])");
    record Hint(String optionId, String source, String quote, String amount, String unit, String suggestedScope) {}

    static List<Hint> from(InferenceRequest request) {
        if (!QuantityEvidenceValidator.required(request) || request.options().size() > 8) return List.of();
        List<Hint> result = new ArrayList<>();
        for (SourceOption option : request.options()) {
            add(result, option.optionId(), "goodsName", request.goodsName(), request);
            add(result, option.optionId(), "optionName1", option.optionName1(), request);
            add(result, option.optionId(), "productCompositionText", request.productCompositionText(), request);
            add(result, option.optionId(), "productNoticeText", request.productNoticeText(), request);
            if (result.size() >= 32) break;
        }
        return List.copyOf(result);
    }

    private static void add(List<Hint> result, String optionId, String source, String text, InferenceRequest request) {
        if (text == null || text.isBlank() || result.size() >= 32) return;
        var counts = COUNTS.matcher(text);
        while (counts.find() && result.size() < 32) {
            String unit = counts.group(2);
            String scope = switch (unit) {
                case "팩", "박스", "세트" -> "TOTAL";
                case "매", "정", "캡슐", "포" -> "PACK";
                case "롤" -> text.contains("팩") ? "PACK" : "TOTAL";
                default -> "UNKNOWN";
            };
            result.add(new Hint(optionId, source, counts.group(), counts.group(1), unit, scope));
        }
        if (!request.allowedPurchaseOptions().contains("개당 중량")
                && !request.allowedPurchaseOptions().contains("개당 용량")) return;
        var physical = PHYSICAL.matcher(text);
        while (physical.find() && result.size() < 32) {
            String unit = physical.group(2).toLowerCase(Locale.ROOT);
            if ((Set.of("g", "kg", "mg").contains(unit) && request.allowedPurchaseOptions().contains("개당 중량"))
                    || (Set.of("ml", "l", "cc").contains(unit) && request.allowedPurchaseOptions().contains("개당 용량")))
                result.add(new Hint(optionId, source, physical.group(), physical.group(1), physical.group(2), "ITEM"));
        }
    }
}
