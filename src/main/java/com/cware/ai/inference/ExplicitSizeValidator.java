package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
import java.util.*;
import java.util.regex.Pattern;

/** 단품명·상품명에 유일하게 명시된 사이즈만 대조한다. 고시에서 값을 채우거나 전체 옵션을 필수화하지 않는다. */
final class ExplicitSizeValidator {
    private ExplicitSizeValidator() {}
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("싱글", "S"), Map.entry("슈퍼싱글", "SS"), Map.entry("퀸", "Q"),
            Map.entry("킹", "K"), Map.entry("슈퍼킹", "SK"), Map.entry("라지킹", "LK"),
            Map.entry("더블", "D"), Map.entry("패밀리", "FAMILY"), Map.entry("프리", "FREE"),
            Map.entry("대형", "L"), Map.entry("중형", "M"), Map.entry("소형", "S"), Map.entry("특대형", "XL"));
    private static final Pattern KOREAN = Pattern.compile(
            "(?<![가-힣])(?:슈퍼싱글|싱글|슈퍼킹|라지킹|퀸|킹|더블|패밀리|프리|특대형|대형|중형|소형)(?=사이즈|[^가-힣]|$)");
    private static final Pattern LATIN = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(?:FAMILY|FREE|XXXL|XXL|XL|XS|SS|SK|LK|S|M|L|Q|K|D)(?![\\p{L}\\p{N}])");
    private static final Set<String> APPAREL = Set.of("XS", "S", "M", "L", "XL", "XXL", "XXXL");
    private static final Pattern NUMERIC_SIZE = Pattern.compile("(?<![0-9.])[0-9]{2,3}(?![0-9]|\\s*(?:mm|cm|ml|g|kg|L|개|매|입))");
    private static final Pattern DIMENSION = Pattern.compile("(?i)[0-9].*(?:mm|cm|㎜|㎝|[x×*])");

    static List<String> validate(InferenceRequest request, MappingProposal proposal) {
        if (proposal == null || proposal.mappings() == null) return List.of();
        var errors = new ArrayList<String>();
        for (String target : request.allowedPurchaseOptions()) {
            if (!target.contains("사이즈")) continue;
            for (var option : request.options()) {
                String expected = expected(request, target, option.optionName1());
                if (expected == null) continue;
                var entries = proposal.mappings().stream().filter(e -> e != null
                        && Objects.equals(e.optionId(), option.optionId()) && target.equals(e.targetPurchaseOptionName())).toList();
                if (entries.isEmpty()) {
                    errors.add("SIZE_VALUE_REQUIRED: 단품명·상품명에 유일한 사이즈가 명시됐지만 매핑이 없습니다.");
                    continue;
                }
                for (var entry : entries) {
                    if (entry.value() == null) continue; // 기본 구조 검증에서 처리한다.
                    String value = entry.value();
                    // 단품명 "90(S)"의 "90"처럼 단품명에 적힌 사이즈 표기 한 조각을 그대로 옮긴 값은 인정한다.
                    if (optionParts(option.optionName1()).contains(compact(value).toUpperCase(Locale.ROOT))) continue;
                    // 매트의 고시 치수는 명칭(더블 등)과 별개로 인정된 제품 규격이다.
                    if (target.contains("매트") && DIMENSION.matcher(value).find()
                            && compact(request.productNoticeText()).contains(compact(value))) continue;
                    if (!sizes(value, target).equals(Set.of(expected)) || !labelOnly(value, expected))
                        errors.add("SIZE_VALUE_INVALID: 유일한 명시 사이즈와 다른 값·치수·목록을 사용할 수 없습니다.");
                }
            }
        }
        return errors.stream().distinct().toList();
    }

    private static String expected(InferenceRequest request, String target, String optionName) {
        Set<String> local = sizes(optionName, target);
        if (!local.isEmpty()) return local.size() == 1 ? local.iterator().next() : null;
        // 상품명 대체는 침구·매트·기저귀처럼 상품명의 사이즈 명칭이 곧 규격인 대상만 쓴다.
        // 의류·잡화 상품명의 "더블 버튼", 시즌 "SS", 판매처 "SK 스토아"를 사이즈로 오인하지 않는다.
        if (!(target.contains("침구") || target.contains("매트") || target.contains("기저귀"))) return null;
        // 단품명에 55·100·230 같은 숫자 사이즈가 있으면 그 단품의 사이즈가 따로 있으므로 상품명을 쓰지 않는다.
        if (optionName != null && NUMERIC_SIZE.matcher(optionName).find()) return null;
        // 상품명 맨 앞의 (SK)와 판매처 표기 SK스토아는 슈퍼킹 근거로 단정하지 않는다.
        String name = request.goodsName() == null ? "" : request.goodsName()
                .replaceFirst("(?i)^(?:\\[[^\\]]*\\]\\s*)*\\(SK\\)\\s*", "")
                .replaceAll("(?i)SK\\s*스토아", "");
        Set<String> common = sizes(name, target);
        if (common.size() != 1) return null;
        // 상품명과 다른 사이즈를 선택하는 단품이 있으면 이름 없는 다른 단품에 공통값을 강제하지 않는다.
        if (request.options().stream().map(o -> sizes(o.optionName1(), target))
                .anyMatch(s -> !s.isEmpty() && !s.equals(common))) return null;
        return common.iterator().next();
    }

    private static Set<String> sizes(String text, String target) {
        var result = new HashSet<String>();
        if (text == null) return result;
        var korean = KOREAN.matcher(text);
        while (korean.find()) {
            String word = korean.group();
            // '대형 가방' 같은 크기 수식어를 잡화 사이즈라고 단정하지 않는다.
            if (Set.of("대형", "중형", "소형", "특대형").contains(word) && !target.contains("기저귀")) continue;
            result.add(ALIASES.get(word));
        }
        var latin = LATIN.matcher(text);
        while (latin.find()) result.add(latin.group().toUpperCase(Locale.ROOT));
        return result;
    }

    private static boolean labelOnly(String value, String expected) {
        String rest = KOREAN.matcher(value).replaceAll("");
        rest = LATIN.matcher(rest).replaceAll("").replace("사이즈", "");
        // 55/S, S(90)처럼 단품명의 의류 호수 병기는 유지한다. 물리 치수 단위는 남으므로 허용되지 않는다.
        if (APPAREL.contains(expected)) rest = rest.replaceAll("[0-9]+", "");
        return rest.replaceAll("[\\s()\\[\\]/-]", "").isEmpty();
    }

    private static Set<String> optionParts(String optionName) {
        var parts = new HashSet<String>();
        if (optionName == null) return parts;
        // 숫자 사이즈(55·90·230)만 조각으로 인정한다. 단품명의 색상 같은 다른 조각은 사이즈 값으로 받지 않는다.
        for (String part : optionName.split("[/()\\[\\]\\s,]+"))
            if (part.matches("[0-9]{2,3}")) parts.add(part);
        return parts;
    }

    private static String compact(String text) { return text == null ? "" : text.replaceAll("\\s+", ""); }
}
