package com.cware.ai.dto;

import java.util.List;

/** 서버의 승인 여부와 별개인 AI 제안 데이터. */
public record AiAssessment(Boolean certain, Double confidence, List<ProposedMapping> mappings, String reason) {
    public record ProposedMapping(String optionId, String targetPurchaseOptionName, String value,
            Double confidence, String evidenceSource, String evidenceText, Calculation calculation) {}
}
