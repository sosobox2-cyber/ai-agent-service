package com.cware.ai.dto;

import java.util.List;

/** 수량 계열의 원문 사실과 최종 매핑 연결. 생략된 매핑의 충돌 근거도 보존한다. */
public record QuantityEvidence(List<Fact> facts,
                               @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                               List<Binding> bindings, List<SourceReview> sourceReviews) {
    public enum Measure { COUNT, WEIGHT, VOLUME }
    public enum Scope { ITEM, PACK, TOTAL }
    public enum Role { MAIN, EXTRA }
    public enum Status { EXTRACTED, NO_RELEVANT_FACT, AMBIGUOUS }
    public record Fact(String id, String optionId, String componentId, Role role, Measure measure,
                       Scope scope, String amount, String unit, String source, String quote) {}
    public record Binding(String optionId, String targetPurchaseOptionName, String componentId,
                          Scope scope, List<String> evidenceIds) {}
    public record SourceReview(String optionId, String source, Status status) {}
}
