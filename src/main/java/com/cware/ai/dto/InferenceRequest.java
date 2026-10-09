package com.cware.ai.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
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
        if (options == null || options.isEmpty()) {
            options = List.of(new SourceOption("1", "단일상품"));
        } else if (options.size() <= 200 && options.stream().allMatch(option -> option != null
                && (option.optionName1() == null || option.optionName1().isBlank()))) {
            String id = options.size() == 1 ? options.get(0).optionId() : null;
            options = List.of(new SourceOption(id == null || id.isBlank() ? "1" : id, "단일상품"));
        }
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
