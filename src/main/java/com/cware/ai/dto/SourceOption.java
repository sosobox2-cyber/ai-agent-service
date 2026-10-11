package com.cware.ai.dto;
import jakarta.validation.constraints.*;
/** 추론 전 원본 단품의 식별자와 옵션 문자열. 한 문자열에서 여러 구매옵션을 추출할 수 있다. */
public record SourceOption(@NotBlank @Size(max=100) String optionId,
    @NotBlank @Size(max=500) String optionName1) {}
