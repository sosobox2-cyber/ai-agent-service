package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
import java.util.Locale;
import java.util.Set;

/** 확인된 원문 문자열 추출 옵션만 LIGHT로 분류한다. 나머지는 정확도를 우선하여 FULL을 쓴다. */
public final class PurchaseOptionPromptSelector {
    private static final Set<String> SIMPLE = Set.of("색상", "컬러", "color", "사이즈", "size",
            "패션의류/잡화 사이즈", "핏");

    public PurchaseOptionPromptMode select(InferenceRequest request) {
        // 단위 설정이나 단일상품 표시가 있으면 상품 문맥 판단이 필요한 FULL로 분류한다.
        if (!request.purchaseOptionUnits().isEmpty() || request.allowedPurchaseOptions().isEmpty()
                || request.options().isEmpty()) return PurchaseOptionPromptMode.FULL;
        if (request.options().stream().anyMatch(o -> o.optionName1() == null || o.optionName1().isBlank()
                || Set.of("단품", "단일상품").contains(o.optionName1().strip())))
            return PurchaseOptionPromptMode.FULL;
        // 모든 허용 옵션명이 단순 추출 대상으로 확인된 경우에만 LIGHT를 선택한다.
        return request.allowedPurchaseOptions().stream().allMatch(name -> SIMPLE.contains(name.toLowerCase(Locale.ROOT)))
                ? PurchaseOptionPromptMode.LIGHT : PurchaseOptionPromptMode.FULL;
    }
}
