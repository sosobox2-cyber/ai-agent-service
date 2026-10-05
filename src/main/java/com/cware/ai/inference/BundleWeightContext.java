package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** 상품명에 명확히 나열된 본품 중량 구성만 한 세트로 합산한다. */
public final class BundleWeightContext {
    private static final Pattern WEIGHT = Pattern.compile(
            "(?<![\\d.\\-])([1-9]\\d{0,5}(?:\\.\\d{1,3})?|0\\.\\d{1,3})\\s*(kg|g)(?![a-zA-Z])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AMBIGUOUS = Pattern.compile(
            "무료|체험|사은|증정|택|선택|랜덤|또는|각|당|[*/×÷]|\\d\\s*(?:개|팩|봉|통|박스|세트|병|상자)|[-−]");

    private BundleWeightContext() {}

    private static BigDecimal total(String text) {
        if (text == null || text.length() > 500 || AMBIGUOUS.matcher(text).find()) return null;
        String[] parts = text.split("\\+", -1);
        if (parts.length < 2 || parts.length > 10) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (String part : parts) {
            var weight = WEIGHT.matcher(part);
            if (!weight.find()) return null;
            // 중량 외 숫자는 구성품 개수, 배수 또는 쉼표 숫자일 수 있어 합산하지 않는다.
            String remainder = part.substring(0, weight.start()) + part.substring(weight.end());
            if (Pattern.compile("\\d").matcher(remainder).find()) return null;
            BigDecimal value = new BigDecimal(weight.group(1));
            if (value.signum() <= 0) return null;
            if (weight.group(2).equalsIgnoreCase("g")) value = value.movePointLeft(3);
            if (weight.find()) return null;
            total = total.add(value);
        }
        return total;
    }

    public static boolean valid(InferenceRequest request, MappingProposal.Entry entry) {
        if (!QuantityContext.eligible(request) || !"goodsName".equals(entry.evidenceSource())
                || !request.goodsName().equals(entry.evidenceText())) return false;
        BigDecimal total = total(request.goodsName());
        if (total == null) return false;
        return switch (entry.targetPurchaseOptionName()) {
            case "수량" -> "1세트".equals(entry.value());
            case "개당 중량" -> (total.stripTrailingZeros().toPlainString() + "kg").equals(entry.value());
            default -> false;
        };
    }

    public static MappingProposal.Entry mockEntry(InferenceRequest request, String target) {
        if (!QuantityContext.eligible(request)) return null;
        BigDecimal total = total(request.goodsName());
        if (total == null) return null;
        String value = switch (target) {
            case "수량" -> "1세트";
            case "개당 중량" -> total.stripTrailingZeros().toPlainString() + "kg";
            default -> null;
        };
        return value == null ? null : new MappingProposal.Entry(request.options().get(0).optionId(),
                target, value, 0.0, "goodsName", request.goodsName());
    }
}
