package com.cware.ai.inference;
import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.AiCallUsage;
import java.util.function.Consumer;
/** 서비스가 AI 구현에 의존하지 않고 매핑 제안과 호출별 사용량을 받는 추론 경계다. */
public interface OptionInferenceGateway {
    MappingProposal infer(InferenceRequest request);
    default MappingProposal infer(InferenceRequest request, Consumer<AiCallUsage> usage) {
        // 사용량 수집을 구현하지 않은 기존 추론기는 기본 호출로 연결한다.
        return infer(request);
    }
}
