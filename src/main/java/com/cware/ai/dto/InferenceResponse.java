package com.cware.ai.dto;
import java.util.List;
/** 적용 가능한 매핑과 AI 제안·서버 판정·호출 사용량을 함께 전달하는 API 응답이다. */
public record InferenceResponse(String goodsId, boolean success, boolean autoApplyCandidate, double confidence,
    List<OptionMapping> optionMappings, List<PurchaseOptionItem> items, String reason,
    List<String> validationErrors, String errorCode, String inputHash, String promptVersion, String inferenceSource,
    AiAssessment aiAssessment, ServerAssessment serverAssessment, List<AiCallUsage> aiUsage) {
    public InferenceResponse {
        // 사용량 목록은 항상 배열로 반환하고 외부에서 원본 목록을 변경해도 응답은 유지한다.
        aiUsage = aiUsage == null ? List.of() : List.copyOf(aiUsage);
    }
    public InferenceResponse(String goodsId, boolean success, boolean autoApplyCandidate, double confidence,
            List<OptionMapping> optionMappings, List<PurchaseOptionItem> items, String reason,
            List<String> validationErrors, String errorCode, String inputHash, String promptVersion, String inferenceSource) {
        this(goodsId, success, autoApplyCandidate, confidence, optionMappings, items, reason, validationErrors,
                errorCode, inputHash, promptVersion, inferenceSource, null, null, List.of());
    }

    /** 기존 결과를 유지한 새 응답에 AI 판단과 서버 판정을 추가한다. */
    public InferenceResponse withAssessments(AiAssessment ai, ServerAssessment server) {
        return new InferenceResponse(goodsId, success, autoApplyCandidate, confidence, optionMappings, items,
                reason, validationErrors, errorCode, inputHash, promptVersion, inferenceSource, ai, server, aiUsage);
    }

    /** 최초 호출과 재시도에서 수집한 호출별 사용량을 새 응답에 추가한다. */
    public InferenceResponse withUsage(List<AiCallUsage> usage) {
        return new InferenceResponse(goodsId, success, autoApplyCandidate, confidence, optionMappings, items,
                reason, validationErrors, errorCode, inputHash, promptVersion, inferenceSource, aiAssessment, serverAssessment, usage);
    }
}
