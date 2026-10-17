package com.cware.ai.dto;

import com.cware.ai.inference.PurchaseOptionPromptMode;
import java.math.BigDecimal;
import java.util.List;
import com.cware.ai.inference.AiRetryReason;

/** API 응답의 usage에서 가져온 현재 요청의 호출별 메트릭. */
public record AiCallUsage(String model, PurchaseOptionPromptMode mode, int attempt,
        Integer input_tokens, Integer cached_tokens, Integer output_tokens, Integer total_tokens,
        BigDecimal estimated_cost_usd, String inferenceId, PurchaseOptionPromptMode initialPromptMode,
        PurchaseOptionPromptMode finalPromptMode, int retryCount, List<AiRetryReason> retryReasons, String status,
        Long elapsedMs) {
    public AiCallUsage(String model, PurchaseOptionPromptMode mode, int attempt,
            Integer input, Integer cached, Integer output, Integer total, BigDecimal cost) {
        this(model, mode, attempt, input, cached, output, total, cost, null, mode, mode,
                attempt - 1, List.of(), null, null);
    }
    /** 측정한 토큰·비용을 유지하면서 같은 상품 추론의 모드 전환과 재시도 사유를 연결한다. */
    public AiCallUsage withInference(String id, PurchaseOptionPromptMode initial, PurchaseOptionPromptMode finalMode,
            int count, List<AiRetryReason> reasons, String callStatus) {
        return new AiCallUsage(model, mode, attempt, input_tokens, cached_tokens, output_tokens,
                total_tokens, estimated_cost_usd, id, initial, finalMode, count, List.copyOf(reasons), callStatus, elapsedMs);
    }
    /** 모델 호출 소요 시간을 밀리초 단위로 추가한 새 사용량 객체를 반환한다. */
    public AiCallUsage withElapsedMs(long elapsed) {
        return new AiCallUsage(model, mode, attempt, input_tokens, cached_tokens, output_tokens,
                total_tokens, estimated_cost_usd, inferenceId, initialPromptMode, finalPromptMode,
                retryCount, retryReasons, status, elapsed);
    }
}
