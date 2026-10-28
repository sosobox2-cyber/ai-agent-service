package com.cware.ai.inference;
import java.util.List;
import com.cware.ai.dto.Calculation;
import com.cware.ai.dto.QuantityEvidence;
import com.fasterxml.jackson.annotation.JsonInclude;
/** LLM은 단품별 구매옵션 값과 수량 판단의 원문 근거를 제안한다. */
public record MappingProposal(Boolean certain, Double confidence, List<Entry> mappings, String reason,
        @JsonInclude(JsonInclude.Include.NON_NULL) QuantityEvidence quantityEvidence) {
    public MappingProposal(Boolean certain, Double confidence, List<Entry> mappings, String reason) {
        this(certain, confidence, mappings, reason, null);
    }
    /**
     * 단품 하나의 구매옵션 제안. 근거·계산 설명이 없는 기존 응답도 생성자로 받을 수 있다.
     * serverNormalization은 서버 규칙이 만들거나 바꾼 매핑의 표시이며 AI 응답에서는 받지 않는다.
     */
    public record Entry(String optionId, String targetPurchaseOptionName, String value, Double confidence,
                        String evidenceSource, String evidenceText, Calculation calculation,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String serverNormalization) {
        public Entry(String optionId, String targetPurchaseOptionName, String value, Double confidence,
                String evidenceSource, String evidenceText, Calculation calculation) {
            this(optionId, targetPurchaseOptionName, value, confidence, evidenceSource, evidenceText, calculation, null);
        }
        public Entry(String optionId, String targetPurchaseOptionName, String value, Double confidence,
                String evidenceSource, String evidenceText) {
            this(optionId, targetPurchaseOptionName, value, confidence, evidenceSource, evidenceText, null);
        }
        public Entry(String optionId, String targetPurchaseOptionName, String value, Double confidence) {
            this(optionId, targetPurchaseOptionName, value, confidence, null, null, null);
        }
        Entry normalizedBy(String rule) {
            return new Entry(optionId, targetPurchaseOptionName, value, confidence, evidenceSource, evidenceText,
                    calculation, rule);
        }
    }
    /** 서버 보정 규칙 이름. 응답의 serverNormalization 값으로 노출한다. */
    static final String UNIT_DISPLAY = "UNIT_DISPLAY";
    static final String IMPLICIT_SINGLE_QUANTITY = "IMPLICIT_SINGLE_QUANTITY";
    static final String PACK_CAPACITY = "PACK_CAPACITY";
    static final String PACK_COUNT_EVIDENCE = "PACK_COUNT_EVIDENCE";
}
