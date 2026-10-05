package com.cware.ai.inference;
import java.util.List;
import com.cware.ai.dto.Calculation;
/** LLM은 단품별 구매옵션 값과 수량 판단의 원문 근거를 제안한다. */
public record MappingProposal(Boolean certain, Double confidence, List<Entry> mappings, String reason) {
    public record Entry(String optionId, String targetPurchaseOptionName, String value, Double confidence,
                        String evidenceSource, String evidenceText, Calculation calculation) {
        public Entry(String optionId, String targetPurchaseOptionName, String value, Double confidence,
                String evidenceSource, String evidenceText) {
            this(optionId, targetPurchaseOptionName, value, confidence, evidenceSource, evidenceText, null);
        }
        public Entry(String optionId, String targetPurchaseOptionName, String value, Double confidence) {
            this(optionId, targetPurchaseOptionName, value, confidence, null, null, null);
        }
    }
}
