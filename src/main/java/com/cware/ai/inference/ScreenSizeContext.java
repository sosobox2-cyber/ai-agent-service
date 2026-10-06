package com.cware.ai.inference;

import com.cware.ai.dto.Calculation;
import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.SourceOption;
import java.util.List;
import java.util.regex.Pattern;

/** 테스트 모드에서 명시된 화면크기 쌍만 직접 추출한다. 인치 환산이나 설치 유형 추측은 하지 않는다. */
final class ScreenSizeContext {
    private static final Pattern SIZE = Pattern.compile("(\\d+(?:\\.\\d+)?)cm\\s*\\((\\d+(?:\\.\\d+)?)인치\\)");
    private ScreenSizeContext() {}

    static MappingProposal.Entry mockEntry(InferenceRequest request, SourceOption option, String target) {
        boolean single = request.options().size() == 1
                && List.of("단품", "단일상품").contains(option.optionName1().strip());
        String source = single ? "goodsName" : "optionName1";
        String text = single ? request.goodsName() : option.optionName1();
        var match = SIZE.matcher(text);
        if (!match.find()) return null;
        String cm = match.group(1), inch = match.group(2), evidenceText = match.group();
        if (match.find()) return null;
        var evidence = new Calculation.Evidence(source, evidenceText);
        String amount;
        String unit;
        switch (target) {
            case "화면크기(cm)" -> { amount = cm; unit = "cm"; }
            case "화면크기(in)" -> { amount = inch; unit = "인치"; }
            case "화면크기 (cm/(인치))" -> {
                return new MappingProposal.Entry(option.optionId(), target, evidenceText, 0.0, source, evidenceText, null);
            }
            default -> { return null; }
        }
        return new MappingProposal.Entry(option.optionId(), target, amount + unit, 0.0, source, evidenceText,
                new Calculation(Calculation.Operation.DIRECT, unit,
                        List.of(new Calculation.Operand(amount, unit, evidence)), null));
    }
}
