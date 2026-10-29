package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 오답률이 높은 구매옵션이 허용된 상품은 검증을 통과해도 자동 적용 후보에서 뺀다(2026-10-29 결정). */
class AutoApplyPolicyTest {
    private static final List<String> HELD = List.of("사이즈", "모델명/품번", "개당 캡슐/정", "침구류사이즈");

    private InferenceResponse infer(List<String> allowed, MappingProposal proposal) {
        var request = new InferenceRequest("policy", "평가 상품", null, allowed,
                List.of(new SourceOption("1", "단일상품")), "검증용 상품 정보", List.of());
        return new PurchaseOptionInferenceService(new RequestValidator(), ignored -> proposal, new ResultValidator(),
                new InputHashService(new ObjectMapper()), Fixtures.properties(HELD)).infer(request);
    }

    @Test void holdsValidatedResultWhenReviewOnlyTargetIsAllowed() {
        var proposal = new MappingProposal(true, .95, List.of(new MappingProposal.Entry("1", "색상", "블랙", .95)), "색상만 판단");
        // 모델명이 허용됐지만 AI가 생략했다. 생략은 승인하되 모델명 대상 상품이므로 자동 적용하지 않는다.
        var held = infer(List.of("색상", "모델명/품번"), proposal);
        assertThat(held.success()).isTrue();
        assertThat(held.autoApplyCandidate()).isFalse();
        assertThat(held.errorCode()).isNull();
        assertThat(held.optionMappings()).extracting(OptionMapping::value).containsExactly("블랙");
        assertThat(held.items()).isNotEmpty();
        assertThat(held.serverAssessment().decisionCode()).isEqualTo("ACCEPTED_REVIEW_POLICY");
        assertThat(held.serverAssessment().reason()).contains("모델명/품번");
    }

    @Test void keepsAutoApplyForOtherTargetsAndOmittedOptions() {
        var proposal = new MappingProposal(true, .95, List.of(new MappingProposal.Entry("1", "색상", "블랙", .95)), "색상만 판단");
        // 위험 대상이 없는 상품은 허용 옵션(패션의류/잡화 사이즈)을 생략해도 기존처럼 자동 적용 후보다.
        var applied = infer(List.of("색상", "패션의류/잡화 사이즈"), proposal);
        assertThat(applied.success()).isTrue();
        assertThat(applied.autoApplyCandidate()).isTrue();
        assertThat(applied.serverAssessment().decisionCode()).isEqualTo("ACCEPTED");
    }

    @Test void rejectionStaysRejection() {
        var proposal = new MappingProposal(true, .50, List.of(new MappingProposal.Entry("1", "색상", "블랙", .50)), "낮은 신뢰도");
        var rejected = infer(List.of("색상", "사이즈"), proposal);
        assertThat(rejected.success()).isFalse();
        assertThat(rejected.autoApplyCandidate()).isFalse();
        assertThat(rejected.serverAssessment().decisionCode()).isEqualTo("LOW_CONFIDENCE");
    }
}
