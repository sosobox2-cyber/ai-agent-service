package com.cware.ai.inference;

import com.cware.ai.dto.*;
import com.cware.ai.dto.QuantityEvidence.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** 수량 계열의 인용·출처 충돌·측정 계층을 검사한다. 원문 전체 추출이나 일반 합산을 재수행하지 않는다. */
public final class QuantityEvidenceValidator {
    private QuantityEvidenceValidator() {}
    public static final Set<String> TARGETS = Set.of("수량", "개당 수량", "개당 중량", "개당 용량");
    private static final List<String> SOURCES = List.of("goodsName", "productNoticeText", "productCompositionText", "optionName1");
    static final Set<String> COUNTS = Set.of("개", "개입", "매", "매입", "장", "정", "정입", "캡슐", "포", "봉", "병", "팩", "롤", "박스", "세트", "묶음", "통", "대", "마리", "미", "패치", "피스", "ea", "p", "t");
    private static final Pattern NUMBER = Pattern.compile("(?:0|[1-9][0-9]{0,11}|[1-9][0-9]{0,2}(?:,[0-9]{3}){1,3})(?:\\.[0-9]{1,6})?");

    public static boolean required(InferenceRequest request) {
        return request.allowedPurchaseOptions().stream().anyMatch(TARGETS::contains);
    }

    public static List<String> validate(InferenceRequest request, MappingProposal proposal) {
        if (!required(request) || proposal == null) return List.of();
        var context = proposal.quantityEvidence();
        if (context == null || context.facts() == null || context.sourceReviews() == null)
            return List.of("QUANTITY_EVIDENCE_REQUIRED: 수량 계열의 출처·계층 근거가 필요합니다.");
        if (context.facts().size() > 1000 || (context.bindings() != null && context.bindings().size() > 800) || context.sourceReviews().size() > 800)
            return List.of("QUANTITY_EVIDENCE_INVALID: 근거 개수 상한을 초과했습니다.");
        List<String> errors = new ArrayList<>();
        Map<String, Fact> facts = new LinkedHashMap<>();
        Map<String, String> values = new HashMap<>();
        Map<String, Role> roles = new HashMap<>();
        for (Fact fact : context.facts()) {
            if (fact == null || blank(fact.id()) || blank(fact.componentId()) || blank(fact.optionId())
                    || fact.role() == null || fact.measure() == null || fact.scope() == null || blank(fact.unit())
                    || blank(fact.source()) || blank(fact.quote()) || fact.quote().length() > 500
                    || fact.id().length() > 100 || fact.componentId().length() > 100) {
                errors.add("QUANTITY_EVIDENCE_INVALID: 근거 필드가 잘못되었습니다."); continue;
            }
            if (facts.putIfAbsent(fact.id(), fact) != null) errors.add("QUANTITY_EVIDENCE_INVALID: 근거 ID가 중복되었습니다.");
            String input = source(request, fact.optionId(), fact.source());
            if (input == null || !normalize(input).contains(normalize(fact.quote())))
                errors.add("QUANTITY_SOURCE_MISMATCH: 인용문이 해당 단품·출처에 없습니다.");
            if (fact.componentId().equals("*") || (fact.role() == Role.EXTRA
                    && !Pattern.compile("사은품|증정|무료체험|별도 추가").matcher(fact.quote()).find()))
                errors.add("QUANTITY_EVIDENCE_INVALID: 구성품 ID 또는 추가 구성 근거가 잘못되었습니다.");
            String roleKey = fact.optionId() + "\u0000" + fact.componentId();
            Role priorRole = roles.putIfAbsent(roleKey, fact.role());
            if (priorRole != null && priorRole != fact.role()) errors.add("QUANTITY_EVIDENCE_INVALID: 같은 구성품의 본품/추가 구분이 다릅니다.");
            if (fact.amount() == null) {
                // 명시적 한 세트 구성의 1세트 판단만 허용한다. 원문에 없는 숫자를 추출 사실로 만들지 않는다.
                if (fact.measure() != Measure.COUNT || fact.scope() != Scope.TOTAL || !fact.unit().equals("세트")
                        || !fact.quote().contains("세트") || Pattern.compile("[0-9]\\s*세트|세트\\s*[0-9]").matcher(fact.quote()).find())
                    errors.add("QUANTITY_EVIDENCE_INVALID: 숫자 없는 근거는 판매 세트 수가 없는 명시적 세트 구성에만 허용합니다.");
                else values.put(fact.id(), "1");
                continue;
            }
            BigDecimal amount = number(fact.amount());
            String canonical = canonical(fact.measure(), fact.amount(), fact.unit());
            if (amount == null || canonical == null || !quotedNumber(fact)) {
                errors.add("QUANTITY_EVIDENCE_INVALID: 근거 숫자·단위가 원문 또는 측정 종류와 맞지 않습니다."); continue;
            }
            values.put(fact.id(), canonical);
        }
        validateReviews(request, context, facts, errors);
        validateExplicitCrossSourceCounts(request, errors);
        // 매핑에서 빠진 항목의 충돌도 검사한다. 같은 본품·같은 측정 계층만 비교한다.
        Map<List<Object>, String> observations = new HashMap<>();
        for (Fact fact : facts.values()) {
            if (!values.containsKey(fact.id())) continue;
            // 매·롤·팩은 모두 COUNT지만 서로 다른 포장 단위다. 같은 계층으로 잘못 표시돼도
            // 70매와 10팩을 숫자만 비교해 충돌이라고 판단하지 않는다.
            List<Object> key = List.of(fact.optionId(), fact.componentId(), fact.measure(), fact.scope(),
                    fact.measure() == Measure.COUNT ? countFamily(fact.unit()) : "physical");
            String previous = observations.putIfAbsent(key, values.get(fact.id()));
            if (previous != null && !previous.equals(values.get(fact.id())))
                errors.add("QUANTITY_SOURCE_CONFLICT: 같은 본품·같은 계층의 원문 수치가 충돌합니다.");
        }
        // 여러 본품의 개당 물리량이 다르면 한 대표값을 골라 전체 상품에 적용하지 않는다.
        for (var option : request.options()) for (String target : List.of("개당 중량", "개당 용량")) {
            if (!request.allowedPurchaseOptions().contains(target)) continue;
            Set<String> components = new HashSet<>(), amounts = new HashSet<>();
            for (Fact fact : facts.values()) if (option.optionId().equals(fact.optionId()) && fact.role() == Role.MAIN
                    && fact.scope() == Scope.ITEM && fact.measure() == measure(target) && values.containsKey(fact.id())) {
                components.add(fact.componentId()); amounts.add(values.get(fact.id()));
            }
            if (components.size() > 1 && amounts.size() > 1)
                errors.add("QUANTITY_MIXED_ITEMS: 서로 다른 본품의 개당 물리량을 하나로 적용할 수 없습니다.");
        }
        Map<String, Binding> bindings = new HashMap<>();
        List<Binding> supplied = context.bindings() == null ? deriveBindings(proposal, facts, errors) : context.bindings();
        for (Binding binding : supplied) {
            if (binding == null || blank(binding.optionId()) || !TARGETS.contains(Objects.toString(binding.targetPurchaseOptionName(), ""))
                    || blank(binding.componentId()) || binding.scope() == null || binding.evidenceIds() == null
                    || binding.evidenceIds().isEmpty() || binding.evidenceIds().stream().anyMatch(QuantityEvidenceValidator::blank)) {
                errors.add("QUANTITY_BINDING_INVALID: 매핑 근거 연결이 잘못되었습니다."); continue;
            }
            if (bindings.putIfAbsent(key(binding.optionId(), binding.targetPurchaseOptionName()), binding) != null)
                errors.add("QUANTITY_BINDING_INVALID: 매핑 근거 연결이 중복되었습니다.");
        }
        if (proposal.mappings() != null) for (var entry : proposal.mappings()) {
            if (entry == null || !TARGETS.contains(Objects.toString(entry.targetPurchaseOptionName(), ""))) continue;
            Binding binding = bindings.remove(key(entry.optionId(), entry.targetPurchaseOptionName()));
            if (binding == null) { errors.add("QUANTITY_BINDING_REQUIRED: 수량 매핑에 원문 근거 연결이 없습니다."); continue; }
            validateBinding(entry, binding, facts, values, errors);
        }
        if (!bindings.isEmpty()) errors.add("QUANTITY_BINDING_INVALID: 존재하지 않는 매핑에 근거를 연결했습니다.");
        return errors.stream().distinct().toList();
    }

    /** 서로 다른 출처가 명시한 포장 수와 매수의 충돌은 AI의 사실 누락과 무관하게 확인한다. */
    private static void validateExplicitCrossSourceCounts(InferenceRequest request, List<String> errors) {
        if (request.options().size() != 1 || request.goodsName() == null || request.productCompositionText() == null
                || request.allowedPurchaseOptions().stream().noneMatch(t -> t.equals("수량") || t.equals("개당 수량"))) return;
        String name = request.goodsName(), composition = request.productCompositionText();
        for (String unit : List.of("팩", "롤", "매")) {
            var pattern = Pattern.compile("(?<![0-9])([1-9][0-9]{0,5})\\s*" + unit);
            Set<String> left = new HashSet<>(), right = new HashSet<>();
            var a = pattern.matcher(name); while (a.find()) left.add(a.group(1));
            var b = pattern.matcher(composition); while (b.find()) right.add(b.group(1));
            if (left.size() == 1 && right.size() == 1 && !left.equals(right))
                errors.add("QUANTITY_SOURCE_CONFLICT: 상품명과 구성의 명시된 " + unit + " 수가 다릅니다.");
        }
        // 단위가 생략된 '16x6'은 구성의 '17매 6팩'처럼 뒤쪽 포장 수가 일치할 때만 매수로 해석한다.
        var compact = Pattern.compile("(?<![0-9])([1-9][0-9]{0,5})\\s*[xX×*]\\s*([1-9][0-9]{0,5})(?![0-9])").matcher(name);
        var explicit = Pattern.compile("(?<![0-9])([1-9][0-9]{0,5})\\s*매\\s*[xX×*]?\\s*([1-9][0-9]{0,5})\\s*팩").matcher(composition);
        while (compact.find()) {
            explicit.reset();
            while (explicit.find()) if (compact.group(2).equals(explicit.group(2)) && !compact.group(1).equals(explicit.group(1)))
                errors.add("QUANTITY_SOURCE_CONFLICT: 상품명과 구성의 포장당 매수가 다릅니다.");
        }
    }

    /** AI가 이미 낸 피연산자와 인용 출처를 원문 사실에 정확히 대조해 연결한다. 모호하면 임의 선택하지 않는다. */
    private static List<Binding> deriveBindings(MappingProposal proposal, Map<String, Fact> facts, List<String> errors) {
        List<Binding> result = new ArrayList<>();
        if (proposal.mappings() == null) return result;
        for (var entry : proposal.mappings()) {
            if (entry == null || !TARGETS.contains(Objects.toString(entry.targetPurchaseOptionName(), ""))) continue;
            var calc = entry.calculation();
            if (calc == null || calc.operands() == null) continue;
            Scope expectedScope = scope(entry.targetPurchaseOptionName());
            Measure expectedMeasure = measure(entry.targetPurchaseOptionName());
            List<String> ids = new ArrayList<>();
            if (calc.operands().isEmpty()) {
                var source = calc.context() == null ? entry.evidenceSource() : calc.context().source();
                var quote = calc.context() == null ? entry.evidenceText() : calc.context().text();
                var matches = facts.values().stream().filter(f -> f.amount() == null && f.role() == Role.MAIN
                        && Objects.equals(f.optionId(), entry.optionId()) && f.scope() == expectedScope
                        && f.measure() == expectedMeasure && Objects.equals(f.source(), source)
                        && !blank(quote) && normalize(f.quote()).contains(normalize(quote))).toList();
                if (matches.size() == 1) ids.add(matches.get(0).id());
            } else {
                for (var operand : calc.operands()) {
                    if (operand == null || operand.evidence() == null || blank(operand.evidence().text())) break;
                    var matches = facts.values().stream().filter(f -> f.role() == Role.MAIN
                            && Objects.equals(f.optionId(), entry.optionId()) && f.scope() == expectedScope
                            && f.measure() == expectedMeasure && Objects.equals(f.amount(), operand.amount())
                            && Objects.equals(f.unit(), operand.unit()) && Objects.equals(f.source(), operand.evidence().source())
                            && normalize(f.quote()).contains(normalize(operand.evidence().text()))).toList();
                    if (matches.size() != 1) break;
                    ids.add(matches.get(0).id());
                }
            }
            if (ids.size() != Math.max(1, calc.operands().size())) {
                errors.add("QUANTITY_BINDING_REQUIRED: 계산 피연산자와 일치하는 고유한 원문 사실이 없습니다.");
                continue;
            }
            String component = ids.stream().map(id -> facts.get(id).componentId()).distinct().count() == 1
                    ? facts.get(ids.get(0)).componentId() : "*";
            result.add(new Binding(entry.optionId(), entry.targetPurchaseOptionName(), component, expectedScope, ids));
        }
        return result;
    }

    private static void validateReviews(InferenceRequest request, QuantityEvidence context, Map<String, Fact> facts, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (SourceReview review : context.sourceReviews()) {
            if (review == null || review.status() == null || source(request, review.optionId(), review.source()) == null
                    || !seen.add(key(review.optionId(), review.source()))) {
                errors.add("QUANTITY_SOURCE_COVERAGE: 출처 검토가 중복되거나 잘못되었습니다."); continue;
            }
            boolean extracted = facts.values().stream().anyMatch(f -> f.optionId().equals(review.optionId()) && f.source().equals(review.source()));
            if ((review.status() == Status.EXTRACTED && !extracted) || (review.status() == Status.NO_RELEVANT_FACT && extracted))
                errors.add("QUANTITY_SOURCE_COVERAGE: 출처 검토 상태와 추출 사실이 다릅니다.");
            if (review.status() == Status.AMBIGUOUS) errors.add("QUANTITY_SOURCE_AMBIGUOUS: 본품·포장·출처 대응에 검토가 필요합니다.");
        }
        for (var option : request.options()) for (String name : SOURCES) {
            String input = source(request, option.optionId(), name);
            if (!blank(input) && !seen.contains(key(option.optionId(), name)))
                errors.add("QUANTITY_SOURCE_COVERAGE: 입력 출처의 검토가 누락되었습니다.");
        }
    }

    private static void validateBinding(MappingProposal.Entry entry, Binding binding, Map<String, Fact> facts,
                                        Map<String, String> values, List<String> errors) {
        String target = entry.targetPurchaseOptionName();
        Scope scope = scope(target); Measure measure = measure(target);
        var calc = entry.calculation();
        if (binding.scope() != scope || calc == null || calc.operation() == null || calc.operands() == null) {
            errors.add("QUANTITY_SCOPE_MISMATCH: 매핑 계층 또는 계산 설명이 맞지 않습니다."); return;
        }
        var selected = new ArrayList<Fact>();
        var unique = new HashSet<String>();
        for (String id : binding.evidenceIds()) {
            Fact fact = facts.get(id);
            if (!unique.add(id) || fact == null || !fact.optionId().equals(entry.optionId()) || fact.role() != Role.MAIN
                    || fact.measure() != measure || fact.scope() != scope
                    || (!binding.componentId().equals(fact.componentId()) && !binding.componentId().equals("*"))) {
                errors.add("QUANTITY_SCOPE_MISMATCH: 근거의 본품·단품·측정 종류·계층이 매핑과 다릅니다."); continue;
            }
            selected.add(fact);
        }
        if (selected.isEmpty()) return;
        if (scope == Scope.ITEM && selected.stream().map(Fact::componentId).distinct().count() > 1)
            errors.add("QUANTITY_MIXED_ITEMS: 다른 본품의 물리량을 개당 값으로 합산할 수 없습니다.");
        if (binding.componentId().equals("*") && !(scope == Scope.TOTAL && measure == Measure.COUNT))
            errors.add("QUANTITY_SCOPE_MISMATCH: 전체 구성 합산은 판매 전체 개수에만 허용합니다.");
        if (binding.componentId().equals("*") && calc.operation() != Calculation.Operation.SUM)
            errors.add("QUANTITY_OPERATION_MISMATCH: 여러 본품의 전체 개수는 SUM으로 설명해야 합니다.");
        if (selected.stream().anyMatch(f -> f.amount() == null)) {
            if (selected.size() != 1 || calc.operation() != Calculation.Operation.PACK_COUNT || !calc.operands().isEmpty()
                    || !"1세트".equals(entry.value()) || !"세트".equals(calc.outputUnit()))
                errors.add("QUANTITY_BINDING_INVALID: 숫자 없는 세트 근거는 1세트 구성 판단에만 사용할 수 있습니다.");
            return;
        }
        Set<Calculation.Operand> operandsSeen = new HashSet<>();
        for (var operand : calc.operands()) {
            if (!operandsSeen.add(operand)) errors.add("QUANTITY_OPERAND_MISMATCH: 같은 피연산자를 반복 사용했습니다.");
            if (operand == null || selected.stream().noneMatch(f -> Objects.equals(f.amount(), operand.amount())
                    && Objects.equals(f.unit(), operand.unit()) && operand.evidence() != null
                    && Objects.equals(f.source(), operand.evidence().source())
                    && normalize(f.quote()).contains(normalize(operand.evidence().text())) && !blank(operand.evidence().text())))
                errors.add("QUANTITY_OPERAND_MISMATCH: 계산 피연산자가 연결된 원문 사실과 다릅니다.");
        }
        if (calc.operands().isEmpty()) errors.add("QUANTITY_OPERAND_MISMATCH: 명시 수치 계산에 피연산자가 없습니다.");
        // 일반 합산은 다시 수행하지 않는다. 단일 사실의 값·단위 관계와 연산 종류만 확인한다.
        if (calc.operation() != Calculation.Operation.SUM) {
            Set<String> amounts = new HashSet<>(); selected.forEach(f -> amounts.add(values.get(f.id())));
            String output = outputCanonical(measure, entry.value(), calc.outputUnit());
            if (amounts.size() != 1 || output == null || !amounts.contains(output) || calc.operands().size() != 1)
                errors.add("QUANTITY_VALUE_MISMATCH: 출력값이 연결된 단일 사실과 일치하지 않습니다.");
            if (measure != Measure.COUNT && selected.stream().anyMatch(f -> !unit(f.unit()).equals(unit(calc.outputUnit())))
                    && calc.operation() != Calculation.Operation.CONVERT)
                errors.add("QUANTITY_OPERATION_MISMATCH: 물리 단위 환산은 CONVERT로 설명해야 합니다.");
        } else {
            if (outputCanonical(measure, entry.value(), calc.outputUnit()) == null || calc.operands().size() < 2)
                errors.add("QUANTITY_OPERATION_MISMATCH: SUM의 출력 종류 또는 피연산자가 잘못되었습니다.");
            Set<String> observations = new HashSet<>();
            for (Fact fact : selected) if (!observations.add(fact.componentId() + "\u0000" + values.get(fact.id())))
                errors.add("QUANTITY_OPERAND_MISMATCH: 같은 본품 사실을 출처만 달리하여 중복 합산했습니다.");
        }
    }

    private static Measure measure(String target) { return target.equals("개당 중량") ? Measure.WEIGHT : target.equals("개당 용량") ? Measure.VOLUME : Measure.COUNT; }
    private static String countFamily(String unit) {
        String normalized = unit(unit);
        return switch (normalized) {
            case "매", "매입", "장" -> "sheet";
            case "개", "개입", "ea", "p", "t" -> "piece";
            default -> normalized;
        };
    }
    private static Scope scope(String target) { return target.equals("수량") ? Scope.TOTAL : target.equals("개당 수량") ? Scope.PACK : Scope.ITEM; }
    private static String source(InferenceRequest request, String optionId, String name) {
        var option = request.options().stream().filter(o -> Objects.equals(o.optionId(), optionId)).findFirst();
        if (option.isEmpty() || name == null) return null;
        return switch (name) {
            case "goodsName" -> request.goodsName(); case "productNoticeText" -> request.productNoticeText();
            case "productCompositionText" -> request.productCompositionText(); case "optionName1" -> option.get().optionName1(); default -> null;
        };
    }
    private static boolean quotedNumber(Fact fact) {
        String suffix = fact.measure() == Measure.COUNT ? "" : "\\s*" + Pattern.quote(fact.unit()) + "(?![a-zA-Z])";
        var matches = Pattern.compile("(?<![0-9.,+\\-])" + Pattern.quote(fact.amount()) + "(?![0-9.,])" + suffix,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(fact.quote());
        while (matches.find()) {
            // 개수로 선언한 사실이 실제로는 g/ml/cm 등의 숫자이면 unit 필드만 바꿔 우회할 수 없다.
            if (fact.measure() != Measure.COUNT || !Pattern.compile("(?i)^\\s*(?:kg|mg|g|ml|l|cc|cm|mm|m|㎎|㎏|㎖|ℓ)(?![a-zA-Z])")
                    .matcher(fact.quote().substring(matches.end())).find()) return true;
        }
        return false;
    }
    private static String outputCanonical(Measure measure, String value, String outputUnit) {
        if (blank(value) || blank(outputUnit) || !value.regionMatches(true, value.length() - outputUnit.length(), outputUnit, 0, outputUnit.length())) return null;
        return canonical(measure, value.substring(0, value.length() - outputUnit.length()).replace(",", "").strip(), outputUnit);
    }
    private static String canonical(Measure measure, String amount, String unit) {
        BigDecimal value = number(amount); if (value == null) return null;
        String u = unit(unit); BigDecimal factor;
        if (measure == Measure.COUNT) { if (!COUNTS.contains(u)) return null; factor = BigDecimal.ONE; }
        else if (measure == Measure.WEIGHT) factor = switch (u) { case "mg", "㎎" -> new BigDecimal("0.001"); case "g" -> BigDecimal.ONE; case "kg", "㎏" -> new BigDecimal("1000"); default -> null; };
        else factor = switch (u) { case "ml", "㎖", "cc" -> BigDecimal.ONE; case "l", "ℓ" -> new BigDecimal("1000"); default -> null; };
        return factor == null ? null : value.multiply(factor).stripTrailingZeros().toPlainString();
    }
    private static BigDecimal number(String text) {
        if (text == null || !NUMBER.matcher(text).matches()) return null;
        BigDecimal value = new BigDecimal(text.replace(",", "")); return value.signum() > 0 ? value : null;
    }
    private static String unit(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }
    private static String normalize(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").strip(); }
    private static String key(String id, String target) { return id + "\u0000" + target; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
