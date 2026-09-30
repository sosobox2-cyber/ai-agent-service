package com.cware.ai.inference;

import com.cware.ai.dto.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ResultValidator {
    public List<String> validateProposal(InferenceRequest request, MappingProposal proposal) {
        List<String> errors = new ArrayList<>();
        if (proposal == null) return List.of("AI 응답이 없습니다.");
        if (proposal.certain() == null || !validConfidence(proposal.confidence()))
            errors.add("판단 여부 또는 confidence 형식이 잘못되었습니다.");
        if (proposal.reason() == null || proposal.reason().isBlank() || proposal.reason().length() > 2000)
            errors.add("추론 사유가 누락되었거나 너무 깁니다.");
        if (proposal.mappings() == null) return List.of("옵션 매핑 목록이 누락되었습니다.");

        Map<String, SourceOption> sources = new HashMap<>();
        for (SourceOption option : request.options()) sources.put(option.optionId(), option);
        Set<String> seen = new HashSet<>();
        Map<String, Set<String>> targets = new HashMap<>();
        Map<String, Set<String>> values = new HashMap<>();
        for (MappingProposal.Entry entry : proposal.mappings()) {
            if (entry == null || entry.optionId() == null || entry.targetPurchaseOptionName() == null
                    || entry.value() == null || entry.value().isBlank()) {
                errors.add("단품 ID, 구매옵션명 또는 추출 값이 누락되었습니다.");
                continue;
            }
            SourceOption source = sources.get(entry.optionId());
            if (source == null) errors.add("원본에 없는 단품 ID를 반환했습니다.");
            else if (!source.optionName1().contains(entry.value()))
                errors.add("원본 옵션명에 없는 값을 반환했습니다.");
            if (!request.allowedPurchaseOptions().contains(entry.targetPurchaseOptionName()))
                errors.add("허용되지 않은 구매옵션명이 반환되었습니다.");
            if (!seen.add(entry.optionId() + "\u0000" + entry.targetPurchaseOptionName()))
                errors.add("같은 단품의 구매옵션명이 중복 매핑되었습니다.");
            targets.computeIfAbsent(entry.optionId(), ignored -> new HashSet<>()).add(entry.targetPurchaseOptionName());
            if (!values.computeIfAbsent(entry.optionId(), ignored -> new HashSet<>()).add(entry.value()))
                errors.add("같은 추출 값을 여러 구매옵션명에 중복 사용했습니다.");
            if (!validConfidence(entry.confidence()))
                errors.add("매핑 confidence는 0~1의 유한한 수여야 합니다.");
        }
        for (SourceOption option : request.options()) {
            Set<String> itemTargets = targets.getOrDefault(option.optionId(), Set.of());
            if (itemTargets.isEmpty()) errors.add("단품의 구매옵션 매핑이 누락되었습니다.");
            if (!itemTargets.containsAll(request.effectiveRequiredOptions())) errors.add("필수 구매옵션이 누락되었습니다.");
        }
        return errors.stream().distinct().toList();
    }

    public List<PurchaseOptionItem> assemble(InferenceRequest request, MappingProposal proposal) {
        Map<String, Map<String, String>> byId = new HashMap<>();
        for (MappingProposal.Entry entry : proposal.mappings())
            byId.computeIfAbsent(entry.optionId(), ignored -> new LinkedHashMap<>())
                    .put(entry.targetPurchaseOptionName(), entry.value());
        return request.options().stream().map(source -> new PurchaseOptionItem(source.optionId(),
                Collections.unmodifiableMap(byId.getOrDefault(source.optionId(), Map.of())))).toList();
    }

    /** 결과 조립 이후에도 ID, 값, 허용 이름 및 단품 조합을 재대조한다. */
    public List<String> validateItems(InferenceRequest request, MappingProposal proposal, List<PurchaseOptionItem> items) {
        List<String> errors = new ArrayList<>();
        if (items == null || items.size() != request.options().size()) return List.of("단품 개수가 원본과 다릅니다.");
        Map<String, PurchaseOptionItem> expected = new HashMap<>();
        for (PurchaseOptionItem item : assemble(request, proposal)) expected.put(item.optionId(), item);
        Set<String> ids = new HashSet<>();
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
            if (!item.purchaseOptions().keySet().containsAll(request.effectiveRequiredOptions()))
                errors.add("필수 구매옵션이 누락되었습니다.");
            if (!combinations.add(item.purchaseOptions())) errors.add("서로 다른 단품이 동일한 구매옵션 조합입니다.");
        }
        if (!ids.equals(expected.keySet())) errors.add("원본 단품 ID가 누락되거나 추가되었습니다.");
        return errors.stream().distinct().toList();
    }

    public static boolean validConfidence(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
