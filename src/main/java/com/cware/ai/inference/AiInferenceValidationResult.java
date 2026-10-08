package com.cware.ai.inference;

import java.util.List;

public record AiInferenceValidationResult(List<ValidationError> errors) {
    public AiInferenceValidationResult { errors = List.copyOf(errors); }
    public boolean valid() { return errors.isEmpty(); }
    public List<AiRetryReason> reasons() { return errors.stream().map(ValidationError::code).distinct().toList(); }
    public record ValidationError(AiRetryReason code, String message) {}
}
