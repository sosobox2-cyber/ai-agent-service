package com.cware.ai.inference;

import com.cware.ai.dto.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** 테스트 모드에서 명시된 롤 길이 × 포장당 롤 수 × 판매 포장 수를 구분한다. */
final class RollPackContext {
    private static final Pattern COMPOSITION = Pattern.compile(
            "(?<![\\d.])((?:[1-9]\\d{0,5}|0)(?:\\.\\d{1,3})?)\\s*(mm|cm|m)\\s*[x×*]\\s*"
            + "([1-9]\\d{0,5})\\s*롤\\s*[x×*]\\s*([1-9]\\d{0,5})\\s*(팩|박스|세트)"
            + "(?:\\s*[(（]\\s*총\\s*([1-9]\\d{0,11})\\s*롤\\s*[)）])?", Pattern.CASE_INSENSITIVE);
    private static final Pattern BONUS = Pattern.compile("무료\\s*체험|체험분|사은품|증정|무료\\s*샘플");

    private RollPackContext() {}

    static MappingProposal.Entry mockEntry(InferenceRequest request, SourceOption option, String target) {
        if (request.options().size() != 1 || !Set.of("단일상품", "단품").contains(option.optionName1().strip())
                || !Set.of("길이", "개당 수량", "수량").contains(target)) return null;
        List<String> dimensions = null;
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
                BigDecimal length = new BigDecimal(match.group(1));
                if (length.signum() <= 0) return null;
                if (match.group(6) != null && new BigDecimal(match.group(3)).multiply(new BigDecimal(match.group(4)))
                        .compareTo(new BigDecimal(match.group(6))) != 0) return null;
                var candidate = List.of(length.stripTrailingZeros().toPlainString(), match.group(2).toLowerCase(Locale.ROOT),
                        match.group(3), match.group(4), match.group(5));
                if (dimensions != null && !dimensions.equals(candidate)) return null;
                if (dimensions == null) {
                    dimensions = candidate;
                    evidence = new Calculation.Evidence(sourceName, match.group());
                }
            }
        }
        if (dimensions == null) return null;
        String amount = switch (target) {
            case "길이" -> dimensions.get(0);
            case "개당 수량" -> dimensions.get(2);
            default -> dimensions.get(3);
        };
        String rawUnit = switch (target) {
            case "길이" -> dimensions.get(1);
            case "개당 수량" -> "롤";
            default -> dimensions.get(4);
        };
        String outputUnit = request.purchaseOptionUnits().stream().filter(unit -> unit.purchaseOptionName().equals(target))
                .findFirst().map(unit -> unit.unitOptions().contains(rawUnit) ? rawUnit : unit.defaultUnit())
                .orElse(target.equals("수량") ? "개" : rawUnit);
        Calculation.Operation operation = switch (target) {
            case "길이" -> Calculation.Operation.DIRECT;
            case "개당 수량" -> Calculation.Operation.PACK_CONTENT;
            default -> Calculation.Operation.PACK_COUNT;
        };
        var calculation = new Calculation(operation, outputUnit,
                List.of(new Calculation.Operand(amount, rawUnit, evidence)),
                target.equals("길이") ? null : evidence);
        return new MappingProposal.Entry(option.optionId(), target, amount + outputUnit, 0.0,
                evidence.source(), evidence.text(), calculation);
    }
}
