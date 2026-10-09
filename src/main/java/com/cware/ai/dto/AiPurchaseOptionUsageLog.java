package com.cware.ai.dto;

import com.cware.ai.inference.AiRetryReason;
import com.cware.ai.inference.PurchaseOptionPromptMode;
import java.math.BigDecimal;
import java.util.List;

/** 한 번의 실제 API 호출에 대한 메타데이터. inferenceId는 상품 추론 요청 ID다. */
public record AiPurchaseOptionUsageLog(String timestamp, String inferenceId, String goodsId, String categoryName,
        PurchaseOptionPromptMode promptMode, String model,
        Integer inputTokens, Integer cachedTokens, Integer outputTokens, Integer totalTokens,
        Boolean certain, Double confidence, Integer mappingCount, long elapsedMs, String status,
        PurchaseOptionPromptMode initialPromptMode, PurchaseOptionPromptMode finalPromptMode,
        int retryCount, AiRetryReason retryReason, List<AiRetryReason> retryReasons,
        Boolean validationPassed, BigDecimal estimatedCostUsd, String apiKeyId) {}
