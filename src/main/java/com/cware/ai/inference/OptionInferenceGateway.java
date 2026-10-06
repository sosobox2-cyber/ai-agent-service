package com.cware.ai.inference;
import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.AiCallUsage;
import java.util.function.Consumer;
public interface OptionInferenceGateway {
    MappingProposal infer(InferenceRequest request);
    default MappingProposal infer(InferenceRequest request, Consumer<AiCallUsage> usage) {
        return infer(request);
    }
}
