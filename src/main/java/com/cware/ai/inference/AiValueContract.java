package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.SourceOption;
import java.util.*;
import java.util.regex.Pattern;

/** AI가 제출한 값과 계산 설명 사이의 제한된 의미 계약. 원문 대조는 수량 계열에서 QuantityEvidenceValidator가 맡는다. */
final class AiValueContract {
    private AiValueContract() {}
    private static final Set<String> COUNTS = Set.of("개", "개입", "매", "매입", "장", "정", "정입",
            "캡슐", "포", "봉", "병", "팩", "롤", "박스", "세트", "묶음", "통", "대", "마리", "미");
    private static final Set<String> PHYSICAL = Set.of("mg", "g", "kg", "톤", "ml", "l", "cc",
            "mm", "cm", "m", "인치", "inch", "in", "㎎", "㎏", "㎖", "ℓ", "㎜", "㎝");
    private static final Pattern PEOPLE = Pattern.compile(
            "(?:최대\\s*)?(?:[1-9][0-9]*(?:\\s*[~～-]\\s*[1-9][0-9]*)?\\s*(?:(?:명|인)(?:용)?)?"
            + "|(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|일|이|삼|사|오|육|칠|팔|구|십)\\s*(?:명|인)(?:용)?)"
            + "(?:\\s*(?:이상|이하|내외))?");
    private static final Pattern CERTIFICATE = Pattern.compile("(?i)^(?:R-[RC]-|MSIP-).+");
    private static final Pattern SIZE_ONLY = Pattern.compile(
            "(?i)(?:FREE|F|프리|X{0,4}[SML]|[2-6]XL|[0-9]+(?:\\.[0-9]+)?(?:MM|CM|호|인치)?)"
            + "(?:\\s*[/~～-]\\s*(?:X{0,4}[SML]|[0-9]+(?:\\.[0-9]+)?(?:MM|CM|호|인치)?))*");
    private static final Set<String> BASIC_COLORS = Set.of("블랙", "화이트", "레드", "블루", "그린", "옐로우",
            "핑크", "퍼플", "브라운", "베이지", "그레이", "네이비", "오렌지", "검정", "흰색", "빨강", "파랑",
            "black", "white", "red", "blue", "green", "yellow", "pink", "purple", "brown", "beige", "gray", "navy", "orange");

    private static boolean invalidColor(String value, String optionName) {
        String text = value.strip();
        if (Set.of("없음", "해당없음", "-").contains(text.replaceAll("\\s+", ""))
                || SIZE_ONLY.matcher(text).matches()) return true;
        // 쉼표 계열은 여러 색 목록으로 본다.
        if (Pattern.compile("[,，、;；|\\r\\n]").matcher(text).find()) return true;
        // `+`, `&`, `·`, 기본 색끼리의 `/`는 투톤 한 색상명(단품명 `화이트+블랙`)에도 쓰인다.
        // 단품 옵션명에 그 값이 그대로 있으면 단품의 색상명으로 인정하고, 아니면 여러 색 목록으로 본다.
        if (optionName != null && optionName.replaceAll("\\s+", "").contains(text.replaceAll("\\s+", ""))) return false;
        if (Pattern.compile("[+&·]").matcher(text).find()) return true;
        String[] parts = text.toLowerCase(Locale.ROOT).split("/", -1);
        return parts.length > 1 && Arrays.stream(parts).allMatch(p -> BASIC_COLORS.contains(p.strip()));
    }

    static List<String> validate(InferenceRequest request, MappingProposal proposal) {
        List<String> errors = new ArrayList<>();
        if (proposal == null || proposal.mappings() == null) return errors;
        for (var entry : proposal.mappings()) {
            if (entry == null || entry.value() == null || entry.targetPurchaseOptionName() == null) continue;
            String target = entry.targetPurchaseOptionName();
            if ((target.contains("색상") || target.contains("컬러") || target.equalsIgnoreCase("color"))
                    && invalidColor(entry.value(), request.options().stream()
                            .filter(o -> o.optionId().equals(entry.optionId())).map(SourceOption::optionName1)
                            .findFirst().orElse(null)))
                errors.add("COLOR_VALUE_INVALID: 색상에 자리표시자·사이즈 또는 여러 색 목록을 사용할 수 없습니다.");
            if (target.equals("사용 인원") && !PEOPLE.matcher(entry.value().strip()).matches())
                errors.add("사용 인원 값에는 인원 수를 나타내는 표현이 필요합니다.");
            if ((target.equals("모델명/품번") || target.equals("모델명"))
                    && CERTIFICATE.matcher(entry.value().strip()).matches()
                    && entry.evidenceText() != null
                    && (entry.evidenceText().contains("등록번호") || entry.evidenceText().contains("인증번호")))
                errors.add("인증·등록번호를 모델명/품번으로 사용할 수 없습니다.");
            var calculation = entry.calculation();
            if (calculation == null || calculation.operands() == null) continue;
            // 단위 미설정 옵션과 알 수 없는 단위에는 임의의 기본 단위/차원을 부여하지 않는다.
            boolean configured = request.purchaseOptionUnits().stream()
                    .anyMatch(unit -> unit.purchaseOptionName().equals(target));
            String output = normalize(calculation.outputUnit());
            if (!configured || !COUNTS.contains(output)) continue;
            // 포장 계산에는 물리량과 개수 근거가 함께 올 수 있다. 모든 피연산자가 물리량인 경우만 차단한다.
            if (!calculation.operands().isEmpty() && calculation.operands().stream()
                    .allMatch(operand -> operand != null && PHYSICAL.contains(normalize(operand.unit()))))
                errors.add("물리량만 있는 계산 근거를 수량 단위로 바꿀 수 없습니다.");
        }
        return errors.stream().distinct().toList();
    }

    private static String normalize(String unit) {
        return unit == null ? "" : unit.strip().toLowerCase(Locale.ROOT);
    }
}
