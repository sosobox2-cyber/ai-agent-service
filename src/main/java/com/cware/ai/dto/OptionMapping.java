package com.cware.ai.dto;
import com.fasterxml.jackson.annotation.JsonInclude;
/** 단품별로 원본 문자열에서 추출한 구매옵션 값을 반환한다. serverNormalization은 서버 규칙이 만들거나 바꾼 값에만 있다. */
public record OptionMapping(String optionId, String sourceOptionName,
        String targetPurchaseOptionName, String value, double confidence, String evidenceSource, String evidenceText,
        Calculation calculation, @JsonInclude(JsonInclude.Include.NON_NULL) String serverNormalization) {
    public OptionMapping(String optionId, String sourceOptionName, String targetPurchaseOptionName, String value,
            double confidence, String evidenceSource, String evidenceText, Calculation calculation) {
        this(optionId, sourceOptionName, targetPurchaseOptionName, value, confidence, evidenceSource, evidenceText,
                calculation, null);
    }
}
