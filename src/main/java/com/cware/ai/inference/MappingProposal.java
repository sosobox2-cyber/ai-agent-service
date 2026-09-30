package com.cware.ai.inference;
import java.util.List;
/** LLM은 각 단품의 원본 문자열에서 추출한 구매옵션 값을 제안한다. */
public record MappingProposal(Boolean certain, Double confidence, List<Entry> mappings, String reason) {
    public record Entry(String optionId, String targetPurchaseOptionName, String value, Double confidence) {}
}
