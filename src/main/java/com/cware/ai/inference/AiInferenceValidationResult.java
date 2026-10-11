package com.cware.ai.inference;

import java.util.List;

/** 재시도 판단에 사용할 검증 오류와 중복을 제거한 사유 코드를 제공한다. */
public record AiInferenceValidationResult(List<ValidationError> errors) {
    public AiInferenceValidationResult { errors = List.copyOf(errors); }
    public boolean valid() { return errors.isEmpty(); }
    public List<AiRetryReason> reasons() { return errors.stream().map(ValidationError::code).distinct().toList(); }
    public record ValidationError(AiRetryReason code, String message) {}
}
