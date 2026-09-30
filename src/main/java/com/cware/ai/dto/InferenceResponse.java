package com.cware.ai.dto;
import java.util.List;
public record InferenceResponse(String goodsId, boolean success, boolean autoApplyCandidate, double confidence,
    List<OptionMapping> optionMappings, List<PurchaseOptionItem> items, String reason,
    List<String> validationErrors, String errorCode, String inputHash, String promptVersion, String inferenceSource) {}
