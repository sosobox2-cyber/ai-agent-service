package com.cware.ai.dto;
import jakarta.validation.constraints.*;
public record SourceOption(@NotBlank @Size(max=100) String optionId,
    @NotBlank @Size(max=500) String optionName1) {}
