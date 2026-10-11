package com.cware.ai.dto;

import jakarta.validation.constraints.*;
import java.util.List;

/** 특정 구매옵션에만 적용하는 단위 선택지와, 선택지로 판단하기 어려울 때의 기본단위다. */
public record PurchaseOptionUnit(
        @NotBlank @Size(max=100) String purchaseOptionName,
        @NotBlank @Size(max=30) String defaultUnit,
        @NotEmpty @Size(max=30) List<@NotBlank @Size(max=30) String> unitOptions) {
}
