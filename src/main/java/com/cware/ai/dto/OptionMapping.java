package com.cware.ai.dto;
/** 단품별로 원본 문자열에서 추출한 구매옵션 값을 반환한다. */
public record OptionMapping(String optionId, String sourceOptionName,
        String targetPurchaseOptionName, String value, double confidence, String evidenceSource, String evidenceText,
        Calculation calculation) {}
