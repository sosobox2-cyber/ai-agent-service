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
    @NotEmpty @Size(min=2, max=20) List<@NotBlank @Size(max=100) String> allowedPurchaseOptions,
    @Size(max=20) List<@NotBlank @Size(max=100) String> requiredPurchaseOptions,
    @NotEmpty @Size(max=200) List<@NotNull @Valid SourceOption> options) {
    public List<String> effectiveRequiredOptions() { return requiredPurchaseOptions == null ? List.of() : requiredPurchaseOptions; }
}
