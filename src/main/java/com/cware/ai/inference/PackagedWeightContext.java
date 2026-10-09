package com.cware.ai.inference;

import com.cware.ai.dto.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** 테스트 모드에서 본품의 개별 중량과 명시된 판매 개수를 추출한다. */
final class PackagedWeightContext {
    private static final Pattern PACKAGE = Pattern.compile(
            "(?<![\\d.-])([1-9]\\d{0,5}(?:\\.\\d{1,3})?|0\\.\\d{1,3})\\s*(kg|g)"
            + "\\s*(?:[x×*]\\s*)?([1-9]\\d{0,5})\\s*(통|병|팩|튜브|봉|박스|미|마리|개(?!입))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BONUS = Pattern.compile("무료\\s*체험|체험분|사은품|증정|무료\\s*샘플|쇼핑백");
    private static final Pattern AMBIGUOUS = Pattern.compile("택|선택|랜덤|또는|각\\s*\\d");

    private PackagedWeightContext() {}

    static MappingProposal.Entry mockEntry(InferenceRequest request, SourceOption option, String target) {
        if (request.options().size() != 1 || !Set.of("단일상품", "단품").contains(option.optionName1().strip())
                || !Set.of("개당 중량", "수산물 중량", "수량").contains(target)) return null;
        List<String> composition = null;
        Calculation.Evidence evidence = null;
        for (String sourceName : List.of("productCompositionText", "goodsName", "productNoticeText")) {
            String text = switch (sourceName) {
                case "goodsName" -> request.goodsName();
                case "productNoticeText" -> request.productNoticeText();
                default -> request.productCompositionText();
            };
            if (text == null) continue;
            var bonus = BONUS.matcher(text);
            if (bonus.find()) text = text.substring(0, bonus.start());
            var match = PACKAGE.matcher(text);
            if (!match.find()) continue;
            if (!sourceName.equals("productNoticeText") && AMBIGUOUS.matcher(text).find()) return null;
            do {
                if (sourceName.equals("productNoticeText") && AMBIGUOUS.matcher(text.substring(
                        Math.max(0, match.start() - 20), Math.min(text.length(), match.end() + 20))).find()) return null;
                if (new BigDecimal(match.group(1)).signum() <= 0) return null;
                var candidate = List.of(new BigDecimal(match.group(1)).stripTrailingZeros().toPlainString(),
                        match.group(2).toLowerCase(Locale.ROOT), match.group(3), match.group(4));
                if (Set.of("미", "마리").contains(candidate.get(3))) {
                    var total = Pattern.compile("총\\s*,?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(kg|g)", Pattern.CASE_INSENSITIVE).matcher(text);
                    var expected = new BigDecimal(candidate.get(0)).multiply(new BigDecimal(candidate.get(2)))
                            .multiply(candidate.get(1).equals("kg") ? new BigDecimal("1000") : BigDecimal.ONE);
                    while (total.find()) {
                        var actual = new BigDecimal(total.group(1)).multiply(total.group(2).equalsIgnoreCase("kg")
                                ? new BigDecimal("1000") : BigDecimal.ONE);
                        if (expected.compareTo(actual) != 0) return null;
                    }
                }
                if (composition != null && !composition.equals(candidate)) return null;
                if (composition == null) {
                    composition = candidate;
                    evidence = new Calculation.Evidence(sourceName, match.group());
                }
            } while (match.find());
        }
        if (composition == null) return null;
        boolean weight = !target.equals("수량");
        String amount = composition.get(weight ? 0 : 2);
        String rawUnit = composition.get(weight ? 1 : 3);
        String outputUnit = request.purchaseOptionUnits().stream().filter(unit -> unit.purchaseOptionName().equals(target))
                .findFirst().map(unit -> unit.unitOptions().contains(rawUnit) ? rawUnit : unit.defaultUnit())
                .orElse(weight ? rawUnit : "개");
        var calculation = new Calculation(weight ? Calculation.Operation.DIRECT : Calculation.Operation.PACK_COUNT,
                outputUnit, List.of(new Calculation.Operand(amount, rawUnit, evidence)), weight ? null : evidence);
        return new MappingProposal.Entry(option.optionId(), target, amount + outputUnit, 0.0,
                evidence.source(), evidence.text(), calculation);
    }
}
