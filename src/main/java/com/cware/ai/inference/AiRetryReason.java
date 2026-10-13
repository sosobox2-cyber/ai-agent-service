package com.cware.ai.inference;

/** 응답 검증 실패를 로그와 사용량 응답에서 일관되게 구분하는 재시도 사유 코드다. */
public enum AiRetryReason {
    CERTAIN_FALSE, MISSING_OPTION_MAPPING, UNKNOWN_OPTION_ID, INVALID_PURCHASE_OPTION,
    DUPLICATE_MAPPING, EMPTY_VALUE, INVALID_RESPONSE, LOW_CONFIDENCE,
    INCONSISTENT_OPTION_COVERAGE
}
