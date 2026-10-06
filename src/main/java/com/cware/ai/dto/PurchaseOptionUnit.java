package com.cware.ai.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record PurchaseOptionUnit(
        @NotBlank @Size(max=100) String purchaseOptionName,
        @NotBlank @Size(max=30) String defaultUnit,
        @NotEmpty @Size(max=30) List<@NotBlank @Size(max=30) String> unitOptions) {
}
