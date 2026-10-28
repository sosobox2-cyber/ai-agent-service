package com.cware.ai.inference;

import com.cware.ai.dto.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
/** AI 제안의 기본 구조와 요청 단위 형식을 확인하고 단품별 결과를 조립·검증한다. */
public class ResultValidator {
    /** 실제 AI의 재추론 판단과 최종 승인에서 같은 의미 계약을 사용한다. 모의 추출에는 적용하지 않는다. */
    public List<String> validateAiProposal(InferenceRequest request, MappingProposal proposal) {
        List<String> errors = new ArrayList<>(validateProposal(request, proposal));
        errors.addAll(AiValueContract.validate(request, proposal));
        errors.addAll(QuantityEvidenceValidator.validate(request, proposal));
        return errors.stream().distinct().toList();
    }

    public List<String> validateProposal(InferenceRequest request, MappingProposal proposal) {
        // AI 응답의 필수 항목과 전체 판단의 confidence, 추론 사유 길이를 검증한다.
        List<String> errors = new ArrayList<>();
        if (proposal == null) return List.of("AI 응답이 없습니다.");
        if (proposal.certain() == null || !validConfidence(proposal.confidence()))
            errors.add("판단 여부 또는 confidence 형식이 잘못되었습니다.");
        if (proposal.reason() == null || proposal.reason().isBlank() || proposal.reason().length() > 2000)
            errors.add("추론 사유가 누락되었거나 너무 깁니다.");
        if (proposal.mappings() == null) return List.of("옵션 매핑 목록이 누락되었습니다.");

        // 반환된 단품 ID가 원본에 존재하는지 조회할 수 있도록 인덱스를 만든다.
        Map<String, SourceOption> sources = new HashMap<>();
        for (SourceOption option : request.options()) sources.put(option.optionId(), option);
        // 단품 ID와 구매옵션명 조합의 중복 및 단품별 매핑 누락을 확인하기 위해 기록한다.
        Set<String> seen = new HashSet<>();
        Map<String, Set<String>> targets = new HashMap<>();
        for (MappingProposal.Entry entry : proposal.mappings()) {
            // 필수 값이 없는 항목은 오류만 기록하고 이후 검증에서 제외한다.
            if (entry == null || entry.optionId() == null || entry.optionId().isBlank()
                    || entry.targetPurchaseOptionName() == null || entry.targetPurchaseOptionName().isBlank()
                    || entry.value() == null || entry.value().isBlank()) {
                errors.add("단품 ID, 구매옵션명 또는 추출 값이 누락되었습니다.");
                continue;
            }
            SourceOption source = sources.get(entry.optionId());
            // 단위가 설정된 구매옵션은 숫자와 허용 단위 형식 및 계산 결과 단위의 일치를 확인한다.
            request.purchaseOptionUnits().stream()
                    .filter(u -> u.purchaseOptionName().equals(entry.targetPurchaseOptionName()))
                    .findFirst().ifPresent(u -> {
                        String selected = UnitSelection.selectedUnit(entry.value(), u);
                        if (selected == null) {
                            errors.add("단위가 설정된 구매옵션 값은 숫자와 허용된 단위를 조합해야 합니다.");
                            return;
                        }
                        // 형식 검사만으로는 '0개'처럼 적용할 수 없는 값이 통과하므로 수치도 확인한다.
                        if (!UnitSelection.positiveAmount(entry.value(), selected))
                            errors.add("구매옵션 값의 수치는 0보다 커야 합니다.");
                        if (entry.calculation() != null && !selected.equals(entry.calculation().outputUnit()))
                            errors.add("구매옵션 값의 단위와 calculation.outputUnit이 일치하지 않습니다.");
                    });
            // 원본 단품과 허용된 구매옵션명에 속하는 매핑인지 확인한다.
            if (source == null) errors.add("원본에 없는 단품 ID를 반환했습니다.");
            // 값의 의미, 원문 근거 및 계산의 타당성은 AI가 판단한다.
            if (!request.allowedPurchaseOptions().contains(entry.targetPurchaseOptionName()))
                errors.add("허용되지 않은 구매옵션명이 반환되었습니다.");
            // 구분 문자로 두 값을 연결하여 같은 단품의 동일 구매옵션 중복을 검사한다.
            if (!seen.add(entry.optionId() + "\u0000" + entry.targetPurchaseOptionName()))
                errors.add("같은 단품의 구매옵션명이 중복 매핑되었습니다.");
            targets.computeIfAbsent(entry.optionId(), ignored -> new HashSet<>()).add(entry.targetPurchaseOptionName());
            // 개별 매핑의 confidence도 0~1 범위의 유한한 수여야 한다.
            if (!validConfidence(entry.confidence()))
                errors.add("매핑 confidence는 0~1의 유한한 수여야 합니다.");
        }
        // 모든 원본 단품에 필수 값 검사를 통과한 매핑이 하나 이상 있는지 확인한다.
        for (SourceOption option : request.options()) {
            Set<String> itemTargets = targets.getOrDefault(option.optionId(), Set.of());
            if (itemTargets.isEmpty()) errors.add("단품의 구매옵션 매핑이 누락되었습니다.");
        }
        // 여러 매핑에서 발생한 동일 오류 메시지는 한 번만 반환한다.
        return errors.stream().distinct().toList();
    }

    public List<PurchaseOptionItem> assemble(InferenceRequest request, MappingProposal proposal) {
        // 매핑을 원본 단품 순서로 묶고 단위 미설정 옵션의 누락 값만 '없음'으로 채운다.
        Map<String, Map<String, String>> byId = new HashMap<>();
        for (MappingProposal.Entry entry : proposal.mappings())
            byId.computeIfAbsent(entry.optionId(), ignored -> new LinkedHashMap<>())
                    .put(entry.targetPurchaseOptionName(), entry.value());
        Set<String> configuredUnits = new HashSet<>();
        request.purchaseOptionUnits().forEach(unit -> configuredUnits.add(unit.purchaseOptionName()));
        return request.options().stream().map(source -> {
            Map<String, String> values = new LinkedHashMap<>(byId.getOrDefault(source.optionId(), Map.of()));
            for (String name : request.allowedPurchaseOptions()) {
                if (!configuredUnits.contains(name)) values.putIfAbsent(name, "없음");
            }
            return new PurchaseOptionItem(source.optionId(), Collections.unmodifiableMap(values));
        }).toList();
    }

    /** 결과 조립 이후에도 ID, 값, 허용 이름 및 단품 조합을 재대조한다. */
    public List<String> validateItems(InferenceRequest request, MappingProposal proposal, List<PurchaseOptionItem> items) {
        List<String> errors = new ArrayList<>();
        if (items == null || items.size() != request.options().size()) return List.of("단품 개수가 원본과 다릅니다.");
        Map<String, PurchaseOptionItem> expected = new HashMap<>();
        for (PurchaseOptionItem item : assemble(request, proposal)) expected.put(item.optionId(), item);
        Set<String> ids = new HashSet<>();
        // 단품 ID가 달라도 최종 구매옵션 조합이 같으면 중복 단품으로 검출한다.
        Set<Map<String, String>> combinations = new HashSet<>();
        for (PurchaseOptionItem item : items) {
            if (item == null || item.purchaseOptions() == null) {
                errors.add("단품 결과가 누락되었습니다.");
                continue;
            }
            if (!ids.add(item.optionId())) errors.add("단품 ID가 중복되었습니다.");
            PurchaseOptionItem original = expected.get(item.optionId());
            if (original == null || !original.purchaseOptions().equals(item.purchaseOptions()))
                errors.add("원본 값이 누락·변경되었거나 새로운 옵션 조합이 생성되었습니다.");
            if (!request.allowedPurchaseOptions().containsAll(item.purchaseOptions().keySet()))
                errors.add("허용되지 않은 구매옵션명입니다.");
            if (!combinations.add(item.purchaseOptions())) errors.add("서로 다른 단품이 동일한 구매옵션 조합입니다.");
        }
        if (!ids.equals(expected.keySet())) errors.add("원본 단품 ID가 누락되거나 추가되었습니다.");
        return errors.stream().distinct().toList();
    }

    public static boolean validConfidence(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
