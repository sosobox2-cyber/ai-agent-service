package com.cware.ai.dto;
import java.util.List;
public record InferenceResponse(String goodsId, boolean success, boolean autoApplyCandidate, double confidence,
    List<OptionMapping> optionMappings, List<PurchaseOptionItem> items, String reason,
    List<String> validationErrors, String errorCode, String inputHash, String promptVersion, String inferenceSource,
    AiAssessment aiAssessment, ServerAssessment serverAssessment, List<AiCallUsage> aiUsage) {
    public InferenceResponse {
        aiUsage = aiUsage == null ? List.of() : List.copyOf(aiUsage);
    }
    public InferenceResponse(String goodsId, boolean success, boolean autoApplyCandidate, double confidence,
            List<OptionMapping> optionMappings, List<PurchaseOptionItem> items, String reason,
            List<String> validationErrors, String errorCode, String inputHash, String promptVersion, String inferenceSource) {
        this(goodsId, success, autoApplyCandidate, confidence, optionMappings, items, reason, validationErrors,
                errorCode, inputHash, promptVersion, inferenceSource, null, null, List.of());
    }

    public InferenceResponse withAssessments(AiAssessment ai, ServerAssessment server) {
        return new InferenceResponse(goodsId, success, autoApplyCandidate, confidence, optionMappings, items,
                reason, validationErrors, errorCode, inputHash, promptVersion, inferenceSource, ai, server, aiUsage);
    }

    public InferenceResponse withUsage(List<AiCallUsage> usage) {
        return new InferenceResponse(goodsId, success, autoApplyCandidate, confidence, optionMappings, items,
                reason, validationErrors, errorCode, inputHash, promptVersion, inferenceSource, aiAssessment, serverAssessment, usage);
    }
}
