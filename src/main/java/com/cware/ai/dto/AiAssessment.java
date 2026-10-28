package com.cware.ai.dto;

import java.util.List;

/** 서버의 승인 여부와 별개인 AI 제안 데이터. */
public record AiAssessment(Boolean certain, Double confidence, List<ProposedMapping> mappings, String reason,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        QuantityEvidence quantityEvidence) {
    public AiAssessment(Boolean certain, Double confidence, List<ProposedMapping> mappings, String reason) {
        this(certain, confidence, mappings, reason, null);
    }
    /** 반려된 결과도 검토할 수 있도록 AI가 제출한 값·근거·계산 설명을 보존한다. */
    public record ProposedMapping(String optionId, String targetPurchaseOptionName, String value,
            Double confidence, String evidenceSource, String evidenceText, Calculation calculation) {}
}
