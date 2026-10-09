package com.cware.ai.inference;

import com.cware.ai.dto.*;
import java.util.*;
import java.util.regex.Pattern;

/** 테스트 모드에서 명시된 롤당 매수 × 판매 롤 수만 추출한다. */
final class SheetRollContext {
    private static final Pattern COMPOSITION = Pattern.compile(
            "(?<![\\d.])([1-9]\\d{0,5})\\s*매\\s*[x×*]\\s*([1-9]\\d{0,5})\\s*롤"
            + "(?!\\s*[x×*]\\s*\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BONUS = Pattern.compile("무료\\s*체험|체험분|사은품|증정|무료\\s*샘플");

    private SheetRollContext() {}

    static MappingProposal.Entry mockEntry(InferenceRequest request, SourceOption option, String target) {
        if (request.options().size() != 1 || !Set.of("단일상품", "단품").contains(option.optionName1().strip())
                || !Set.of("개당 수량", "수량").contains(target)) return null;
        List<String> counts = null;
        Calculation.Evidence evidence = null;
        for (String sourceName : List.of("goodsName", "productNoticeText", "productCompositionText")) {
            String text = switch (sourceName) {
                case "goodsName" -> request.goodsName();
                case "productNoticeText" -> request.productNoticeText();
                default -> request.productCompositionText();
            };
            if (text == null) continue;
            var bonus = BONUS.matcher(text);
            if (bonus.find()) text = text.substring(0, bonus.start());
            var match = COMPOSITION.matcher(text);
            while (match.find()) {
                var candidate = List.of(match.group(1), match.group(2));
                if (counts != null && !counts.equals(candidate)) return null;
                if (counts == null) {
                    counts = candidate;
                    evidence = new Calculation.Evidence(sourceName, match.group());
                }
            }
        }
        if (counts == null) return null;
        boolean contents = target.equals("개당 수량");
        String amount = counts.get(contents ? 0 : 1);
        String rawUnit = contents ? "매" : "롤";
        String outputUnit = request.purchaseOptionUnits().stream().filter(unit -> unit.purchaseOptionName().equals(target))
                .findFirst().map(unit -> unit.unitOptions().contains(rawUnit) ? rawUnit : unit.defaultUnit())
                .orElse(contents ? rawUnit : "개");
        var calculation = new Calculation(contents ? Calculation.Operation.PACK_CONTENT : Calculation.Operation.PACK_COUNT,
                outputUnit, List.of(new Calculation.Operand(amount, rawUnit, evidence)), evidence);
        return new MappingProposal.Entry(option.optionId(), target, amount + outputUnit, 0.0,
                evidence.source(), evidence.text(), calculation);
    }
}
