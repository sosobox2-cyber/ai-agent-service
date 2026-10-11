package com.cware.ai.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
/** 상품 원문, 원본 단품, 허용 구매옵션 및 선택적인 단위 설정을 받는 API 입력이다. */
public record InferenceRequest(
    @NotBlank @Size(max=100) String goodsId,
    @NotBlank @Size(max=500) String goodsName,
    @Size(max=500) String categoryName,
    @NotEmpty @Size(min=1, max=200) List<@NotBlank @Size(max=100) String> allowedPurchaseOptions,
    @NotEmpty @Size(max=200) List<@NotNull @Valid SourceOption> options,
    @NotBlank @Size(max=20000) String productNoticeText,
    @Size(max=200) List<@NotNull @Valid PurchaseOptionUnit> purchaseOptionUnits,
    @Size(max=20000) String productCompositionText) {
    public InferenceRequest {
        // 단품이 없거나 모든 옵션명이 비어 있으면 하나의 '단일상품'으로 정규화한다.
        if (options == null || options.isEmpty()) {
            options = List.of(new SourceOption("1", "단일상품"));
        } else if (options.size() <= 200 && options.stream().allMatch(option -> option != null
                && (option.optionName1() == null || option.optionName1().isBlank()))) {
            String id = options.size() == 1 ? options.get(0).optionId() : null;
            options = List.of(new SourceOption(id == null || id.isBlank() ? "1" : id, "단일상품"));
        }
        // 기본단위 '없음'은 단위 제한을 사용하지 않는 입력으로 처리한다.
        // 그 외 불완전한 항목은 남겨 두어 Bean Validation에서 오류를 확인할 수 있도록 한다.
        purchaseOptionUnits = purchaseOptionUnits == null ? List.of() : purchaseOptionUnits.stream()
                .filter(unit -> unit == null || unit.defaultUnit() == null
                        || !"없음".equals(unit.defaultUnit().strip()))
                .toList();
    }

    public InferenceRequest(String goodsId, String goodsName, String categoryName, List<String> allowedPurchaseOptions,
            List<SourceOption> options, String productNoticeText, List<PurchaseOptionUnit> purchaseOptionUnits) {
        this(goodsId, goodsName, categoryName,
                allowedPurchaseOptions, options, productNoticeText, purchaseOptionUnits, null);
    }

    public InferenceRequest(String goodsId, String goodsName, String categoryName, List<String> allowedPurchaseOptions,
            List<SourceOption> options, String productNoticeText) {
        this(goodsId, goodsName, categoryName,
                allowedPurchaseOptions, options, productNoticeText, List.of());
    }
}
