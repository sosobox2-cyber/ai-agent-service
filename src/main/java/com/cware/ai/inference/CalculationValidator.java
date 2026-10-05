package com.cware.ai.inference;

import com.cware.ai.dto.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** 상품별 문구 대신 원문 숫자, 단위 차원 및 공통 연산을 검증한다. */
public final class CalculationValidator {
    private static final String NUMBER = "(?:\\d{1,3}(?:,\\d{3})+|\\d{1,9})(?:\\.\\d{1,6})?";
    private static final Pattern MEASURE = Pattern.compile("(?<![\\d.,-])(" + NUMBER
            + ")\\s*(kg|mg|g|ml|l|개입|매입|세트|박스|상자|튜브|패치|피스|개|매|팩|봉|통|병)(?![a-zA-Z])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BONUS = Pattern.compile("무료\\s*체험|체험분|사은품|증정|무료\\s*샘플");
    private static final Pattern PACK = Pattern.compile("세트|박스|상자|팩|봉|통|병|튜브|개당|포장당|묶음");
    private static final Pattern AMBIGUOUS = Pattern.compile("택\\s*\\d|선택|랜덤|또는|[×*/÷]");
    private static final Map<String, Unit> UNITS = units();
    private record Unit(String name, String dimension, BigDecimal factor) {}
    public record Result(String value, Calculation calculation, String error) {
        public boolean valid() { return error == null; }
    }
    private CalculationValidator() {}

    private static Map<String, Unit> units() {
        var units = new LinkedHashMap<String, Unit>();
        units.put("kg", new Unit("kg", "mass", new BigDecimal("1000")));
        units.put("g", new Unit("g", "mass", BigDecimal.ONE));
        units.put("mg", new Unit("mg", "mass", new BigDecimal("0.001")));
        units.put("l", new Unit("L", "volume", new BigDecimal("1000")));
        units.put("ml", new Unit("ml", "volume", BigDecimal.ONE));
        for (String name : List.of("개", "매", "패치", "피스", "개입", "매입"))
            units.put(name, new Unit(name, "count", BigDecimal.ONE));
        for (String name : List.of("세트", "박스", "상자", "팩", "봉", "통", "병", "튜브"))
            units.put(name, new Unit(name, "pack", BigDecimal.ONE));
        return Collections.unmodifiableMap(units);
    }
    public static List<String> supportedUnits() { return UNITS.values().stream().map(Unit::name).toList(); }
    public static List<Calculation.Operand> operandsFrom(String source, String text) {
        var result = new ArrayList<Calculation.Operand>();
        var matcher = MEASURE.matcher(text);
        while (matcher.find()) result.add(new Calculation.Operand(matcher.group(1), unit(matcher.group(2)).name(),
                new Calculation.Evidence(source, matcher.group())));
        return result;
    }

    private static Unit unit(String name) {
        Unit result = name == null ? null : UNITS.get(name.strip().toLowerCase(Locale.ROOT));
        if (result == null) throw new IllegalArgumentException("지원하지 않는 단위입니다: " + name);
        return result;
    }
    private static BigDecimal number(String text) {
        if (text == null || !text.matches(NUMBER)) throw new IllegalArgumentException("숫자 형식이 유효하지 않습니다.");
        BigDecimal result = new BigDecimal(text.replace(",", ""));
        if (result.signum() <= 0) throw new IllegalArgumentException("수량·중량·용량은 양수여야 합니다.");
        return result;
    }
    private static String compact(String text) { return text.replaceAll("[\\s\\u00a0]+", ""); }
    private static boolean containsEvidence(String source, String text) {
        String haystack = compact(source), needle = compact(text);
        for (int start = haystack.indexOf(needle); start >= 0; start = haystack.indexOf(needle, start + 1)) {
            int end = start + needle.length();
            if (Character.isDigit(needle.charAt(0)) && start > 0
                    && (Character.isDigit(haystack.charAt(start - 1)) || ".,-".indexOf(haystack.charAt(start - 1)) >= 0)) continue;
            if (end < haystack.length() && Character.isDigit(needle.charAt(needle.length() - 1))
                    && (Character.isDigit(haystack.charAt(end)) || haystack.charAt(end) == '.')) continue;
            if (end < haystack.length() && haystack.charAt(end) == '입' && (needle.endsWith("개") || needle.endsWith("매"))) continue;
            return true;
        }
        return false;
    }
    private static String mainText(String text) {
        if (text == null) return "";
        var bonus = BONUS.matcher(text);
        return bonus.find() ? text.substring(0, bonus.start()) : text;
    }
    private static Calculation.Evidence resolve(InferenceRequest request, String optionId, Calculation.Evidence evidence) {
        if (evidence == null || evidence.text() == null || evidence.text().isBlank() || evidence.text().length() > 500
                || !List.of("goodsName", "productNoticeText", "optionName1").contains(evidence.source() == null ? "" : evidence.source()))
            throw new IllegalArgumentException("원문 근거의 출처 또는 문구가 누락·잘못되었습니다.");
        String optionText = request.options().stream().filter(o -> o.optionId().equals(optionId))
                .map(SourceOption::optionName1).findFirst().orElse("");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("optionName1", optionText);
        if (QuantityContext.eligible(request)) {
            sources.put("goodsName", request.goodsName());
            sources.put("productNoticeText", request.productNoticeText());
        }
        List<String> order = new ArrayList<>(sources.keySet());
        if (order.remove(evidence.source())) order.add(0, evidence.source());
        for (String source : order) {
            if (containsEvidence(mainText(sources.get(source)), evidence.text()))
                return new Calculation.Evidence(source, evidence.text());
        }
        throw new IllegalArgumentException("제출된 근거 문구를 해당 단품의 원문에서 찾을 수 없습니다.");
    }
    private static Unit sourceUnit(String text, BigDecimal amount, Unit expectedUnit, String target) {
        Unit candidate = null;
        var matcher = MEASURE.matcher(text);
        while (matcher.find()) if (number(matcher.group(1)).compareTo(amount) == 0) {
            Unit actual = unit(matcher.group(2));
            if (actual.name().equals(expectedUnit.name())) return actual;
            if (compatible(actual, expectedUnit, target)) {
                if (candidate != null && !candidate.name().equals(actual.name()))
                    throw new IllegalArgumentException("같은 숫자에 서로 다른 단위가 있어 원문 단위를 확정할 수 없습니다.");
                candidate = actual;
            }
        }
        if (candidate == null) throw new IllegalArgumentException("피연산자의 숫자·단위가 제출된 원문 근거와 일치하지 않습니다.");
        return candidate;
    }
    private static boolean compatible(Unit from, Unit to, String target) {
        if (from.dimension().equals(to.dimension()))
            return !from.dimension().equals("pack") || from.name().equals(to.name());
        return target.equals("수량") && from.dimension().equals("pack") && to.name().equals("개");
    }
    private static void targetUnit(String target, Unit output) {
        boolean valid = switch (target) {
            case "수량" -> output.dimension().equals("pack") || output.name().equals("개");
            case "개당 수량" -> List.of("개입", "매입").contains(output.name());
            case "개당 용량" -> output.dimension().equals("volume");
            case "개당 중량" -> output.dimension().equals("mass");
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("구매옵션명과 출력 단위의 종류가 맞지 않습니다.");
    }

    public static Result evaluate(InferenceRequest request, MappingProposal.Entry entry) {
        try {
            Calculation calc = entry.calculation();
            if (calc == null || calc.operation() == null || calc.operands() == null || calc.operands().size() > 20)
                throw new IllegalArgumentException("연산 종류와 피연산자 목록이 필요합니다.");
            Unit output = unit(calc.outputUnit());
            Unit submittedOutput = output;
            targetUnit(entry.targetPurchaseOptionName(), output);
            Calculation.Evidence context = calc.context() == null ? null : resolve(request, entry.optionId(), calc.context());
            var operands = new ArrayList<Calculation.Operand>();
            BigDecimal sum = BigDecimal.ZERO;
            Set<String> seen = new HashSet<>();
            var operandMeasures = new ArrayList<String>();
            for (Calculation.Operand operand : calc.operands()) {
                if (operand == null) throw new IllegalArgumentException("피연산자가 누락되었습니다.");
                BigDecimal amount = number(operand.amount());
                Unit input = unit(operand.unit());
                if ((input.dimension().equals("count") || input.dimension().equals("pack")) && amount.stripTrailingZeros().scale() > 0)
                    throw new IllegalArgumentException("수량은 정수여야 합니다.");
                var submittedEvidence = operand.evidence();
                if (context != null && submittedEvidence != null)
                    submittedEvidence = new Calculation.Evidence(context.source(), submittedEvidence.text());
                var evidence = resolve(request, entry.optionId(), submittedEvidence);
                input = sourceUnit(evidence.text(), amount, input, entry.targetPurchaseOptionName());
                if (entry.targetPurchaseOptionName().equals("수량") && input.name().endsWith("입"))
                    throw new IllegalArgumentException("포장 내 수량을 판매 개수로 직접 사용할 수 없습니다.");
                if (!compatible(input, output, entry.targetPurchaseOptionName()))
                    throw new IllegalArgumentException("서로 다른 종류의 단위는 환산·합산할 수 없습니다.");
                if (!seen.add(evidence.source() + ":" + compact(evidence.text())))
                    throw new IllegalArgumentException("동일한 원문 근거를 중복 계산할 수 없습니다.");
                if (context != null && (!context.source().equals(evidence.source())
                        || !compact(context.text()).contains(compact(evidence.text()))))
                    throw new IllegalArgumentException("피연산자가 구성 근거 범위에 포함되어 있지 않습니다.");
                sum = sum.add(amount.multiply(input.factor()));
                operandMeasures.add(amount.stripTrailingZeros().toPlainString() + ":" + input.name());
                operands.add(new Calculation.Operand(amount.stripTrailingZeros().toPlainString(), input.name(), evidence));
            }
            // 원문이 명확한 개수/매수인 경우 포장 표시 단위를 정리한다. 숫자는 그대로 유지한다.
            if (entry.targetPurchaseOptionName().equals("개당 수량") && operands.size() == 1) {
                String rawUnit = operands.get(0).unit();
                if (List.of("패치", "피스", "개입").contains(rawUnit)) output = unit("개입");
                else if (List.of("매", "매입").contains(rawUnit)) output = unit("매입");
            }
            BigDecimal value;
            switch (calc.operation()) {
                case DIRECT, CONVERT -> {
                    if (operands.size() != 1) throw new IllegalArgumentException("직접 추출·환산에는 피연산자 하나가 필요합니다.");
                    if (entry.targetPurchaseOptionName().equals("개당 수량") && !operands.get(0).unit().endsWith("입"))
                        throw new IllegalArgumentException("전체 개수를 포장 내 수량으로 바꾸려면 PACK_CONTENT와 포장 근거가 필요합니다.");
                    value = sum.divide(output.factor());
                }
                case SUM -> {
                    if (operands.size() < 2 || context == null || AMBIGUOUS.matcher(context.text()).find()
                            || !(context.text().contains("+") || context.text().contains("구성")))
                        throw new IllegalArgumentException("합산에는 두 개 이상 본품과 명확한 구성 근거가 필요합니다.");
                    var sourceMeasures = new ArrayList<String>();
                    var matcher = MEASURE.matcher(context.text());
                    while (matcher.find()) {
                        Unit found = unit(matcher.group(2));
                        if (!output.dimension().equals("count") && List.of("count", "pack").contains(found.dimension()))
                            throw new IllegalArgumentException("구성품별 개수·배수가 섞인 합산은 지원하지 않습니다.");
                        if (found.dimension().equals(output.dimension())) sourceMeasures.add(
                                number(matcher.group(1)).stripTrailingZeros().toPlainString() + ":" + found.name());
                    }
                    Collections.sort(sourceMeasures);
                    Collections.sort(operandMeasures);
                    if (!sourceMeasures.equals(operandMeasures))
                        throw new IllegalArgumentException("구성 범위의 숫자와 합산 피연산자가 다릅니다. 누락·중복을 확인하세요.");
                    value = sum.divide(output.factor());
                }
                case PACK_COUNT -> {
                    if (!entry.targetPurchaseOptionName().equals("수량") || !output.dimension().equals("pack")
                            || context == null || AMBIGUOUS.matcher(context.text()).find())
                        throw new IllegalArgumentException("판매 묶음 수에는 원문의 포장 단위와 구성 근거가 필요합니다.");
                    boolean namedPack = context.text().contains(output.name());
                    var components = MEASURE.matcher(context.text());
                    int componentCount = 0;
                    while (components.find()) componentCount++;
                    boolean combinedSet = output.name().equals("세트") && context.text().contains("+") && componentCount >= 2;
                    if (!namedPack && !combinedSet)
                        throw new IllegalArgumentException("원문에 판매 포장 단위 또는 함께 판매되는 구성품이 없습니다.");
                    if (operands.isEmpty()) {
                        String saleText = request.options().stream().filter(o -> o.optionId().equals(entry.optionId()))
                                .map(SourceOption::optionName1).findFirst().orElse("");
                        if (QuantityContext.eligible(request)) saleText += " " + mainText(request.goodsName());
                        var saleCounts = MEASURE.matcher(saleText);
                        while (saleCounts.find()) if (unit(saleCounts.group(2)).dimension().equals("pack")
                                && number(saleCounts.group(1)).compareTo(BigDecimal.ONE) != 0)
                            throw new IllegalArgumentException("원본 판매 수량이 여러 묶음이므로 1묶음으로 추론할 수 없습니다.");
                        var counts = MEASURE.matcher(context.text());
                        while (counts.find()) if (unit(counts.group(2)).dimension().equals("pack")
                                && number(counts.group(1)).compareTo(BigDecimal.ONE) != 0)
                            throw new IllegalArgumentException("여러 묶음의 판매 수량을 1묶음으로 바꿀 수 없습니다.");
                        value = BigDecimal.ONE;
                    } else {
                        if (operands.size() != 1 || !unit(operands.get(0).unit()).dimension().equals("pack"))
                            throw new IllegalArgumentException("명시된 판매 묶음 수에는 포장 단위 피연산자 하나가 필요합니다.");
                        value = sum.divide(output.factor());
                    }
                }
                case PACK_CONTENT -> {
                    if (!entry.targetPurchaseOptionName().equals("개당 수량") || operands.size() != 1 || context == null
                            || !PACK.matcher(context.text()).find() || AMBIGUOUS.matcher(context.text()).find())
                        throw new IllegalArgumentException("포장 내 수량에는 피연산자 하나와 포장 기준 근거가 필요합니다.");
                    value = sum.divide(output.factor());
                }
                default -> throw new IllegalArgumentException("지원하지 않는 연산입니다.");
            }
            if ((output.dimension().equals("count") || output.dimension().equals("pack")) && value.stripTrailingZeros().scale() > 0)
                throw new IllegalArgumentException("최종 수량은 정수여야 합니다.");
            var claimed = MEASURE.matcher(entry.value() == null ? "" : entry.value().strip());
            if (!claimed.matches() || number(claimed.group(1)).compareTo(value) != 0
                    || !(unit(claimed.group(2)).name().equals(output.name())
                        || (output.dimension().equals("count") && submittedOutput.dimension().equals("count")
                            && unit(claimed.group(2)).name().equals(submittedOutput.name()))))
                throw new IllegalArgumentException("AI 제안 값이 서버 재계산 결과 " + value.stripTrailingZeros().toPlainString() + output.name() + "와 다릅니다.");
            return new Result(value.stripTrailingZeros().toPlainString() + output.name(),
                    new Calculation(calc.operation(), output.name(), List.copyOf(operands), context), null);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return new Result(null, null, e.getMessage());
        }
    }
}
