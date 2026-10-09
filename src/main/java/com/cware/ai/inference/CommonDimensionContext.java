package com.cware.ai.inference;

import com.cware.ai.dto.*;
import java.util.Set;
import java.util.regex.Pattern;

/** 테스트 모드에서 색상만 다른 단품의 단일 공통 치수와 명시된 공통 개수를 처리한다. */
final class CommonDimensionContext {
    private static final Pattern SIZE = Pattern.compile("(?:치수|크기|규격)\\s*:\\s*(\\d+(?:\\.\\d+)?\\s*[xX×*]\\s*\\d+(?:\\.\\d+)?(?:\\s*[xX×*]\\s*\\d+(?:\\.\\d+)?)?\\s*(?:cm|mm)(?:\\s*\\([^()\\r\\n]*\\))?)\\s*(?=,\\s*\\d{3}:|$)");
    static MappingProposal.Entry mockEntry(InferenceRequest request, SourceOption option, String target, Set<String> colors) {
        if (request.productNoticeText() == null || request.options().stream()
                .anyMatch(o -> !colors.contains(o.optionName1().strip()))) return null;
        var matcher = SIZE.matcher(request.productNoticeText().strip().replaceAll("^\"|\"$", ""));
        if (!matcher.find()) return null;
        String dimension = matcher.group(1);
        if (matcher.find()) return null;
        if ("사이즈".equals(target) && request.purchaseOptionUnits().stream()
                .noneMatch(u -> target.equals(u.purchaseOptionName())))
            return new MappingProposal.Entry(option.optionId(), target, dimension, 0.0,
                    "productNoticeText", dimension, null);
        if (!"수량".equals(target) || request.productCompositionText() == null) return null;
        var count = Pattern.compile("^[\\s\"]*[^\\d+\\r\\n]+\\s+([1-9]\\d*)개[\\s\"]*$")
                .matcher(request.productCompositionText());
        if (!count.matches()) return null;
        var units = request.purchaseOptionUnits().stream().filter(u -> target.equals(u.purchaseOptionName())).findFirst();
        if (units.isPresent() && !"개".equals(units.get().defaultUnit()) && !units.get().unitOptions().contains("개")) return null;
        var evidence = new Calculation.Evidence("productCompositionText", request.productCompositionText().strip());
        return new MappingProposal.Entry(option.optionId(), target, count.group(1) + "개", 0.0,
                evidence.source(), evidence.text(), new Calculation(Calculation.Operation.DIRECT, "개",
                java.util.List.of(new Calculation.Operand(count.group(1), "개", evidence)), null));
    }
}
