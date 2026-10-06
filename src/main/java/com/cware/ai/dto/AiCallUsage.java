package com.cware.ai.dto;

import com.cware.ai.inference.PurchaseOptionPromptMode;
import java.math.BigDecimal;

/** API 응답의 usage에서 가져온 현재 요청의 호출별 메트릭. */
public record AiCallUsage(String model, PurchaseOptionPromptMode mode, int attempt,
        Integer input_tokens, Integer cached_tokens, Integer output_tokens, Integer total_tokens,
        BigDecimal estimated_cost_usd) {}
