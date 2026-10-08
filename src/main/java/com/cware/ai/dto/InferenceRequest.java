package com.cware.ai.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record InferenceRequest(
    @NotBlank @Size(max=100) String goodsId,
    @NotBlank @Size(max=500) String goodsName,
    @Size(max=200) String brand,
    @NotBlank @Size(max=500) String categoryName,
    @NotBlank @Size(max=100) String coupangCategoryId,
    @NotBlank @Size(max=500) String coupangCategoryName,
    @NotEmpty @Size(min=1, max=20) List<@NotBlank @Size(max=100) String> allowedPurchaseOptions,
    @NotEmpty @Size(max=200) List<@NotNull @Valid SourceOption> options,
    @Size(max=20000) String productNoticeText,
    @Size(max=20) List<@NotNull @Valid PurchaseOptionUnit> purchaseOptionUnits,
    @Size(max=20000) String productCompositionText) {
    public InferenceRequest {
        purchaseOptionUnits = purchaseOptionUnits == null ? List.of() : purchaseOptionUnits;
    }

    public InferenceRequest(String goodsId, String goodsName, String brand, String categoryName,
            String coupangCategoryId, String coupangCategoryName, List<String> allowedPurchaseOptions,
            List<SourceOption> options, String productNoticeText, List<PurchaseOptionUnit> purchaseOptionUnits) {
        this(goodsId, goodsName, brand, categoryName, coupangCategoryId, coupangCategoryName,
                allowedPurchaseOptions, options, productNoticeText, purchaseOptionUnits, null);
    }

    public InferenceRequest(String goodsId, String goodsName, String brand, String categoryName,
            String coupangCategoryId, String coupangCategoryName, List<String> allowedPurchaseOptions,
            List<SourceOption> options, String productNoticeText) {
        this(goodsId, goodsName, brand, categoryName, coupangCategoryId, coupangCategoryName,
                allowedPurchaseOptions, options, productNoticeText, List.of());
    }
}
