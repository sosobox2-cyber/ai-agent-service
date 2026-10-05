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
    @Size(max=20000) String productNoticeText) {
}
