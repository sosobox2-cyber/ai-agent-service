package com.cware.ai.service;

import com.cware.ai.config.InferenceProperties;
import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.cware.ai.util.InputHashService;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class PurchaseOptionInferenceService {
    private final RequestValidator requestValidator;
    private final OptionInferenceGateway ai;
    private final ResultValidator results;
    private final InputHashService hashes;
    private final InferenceProperties properties;

    public PurchaseOptionInferenceService(RequestValidator requestValidator,
            OptionInferenceGateway ai, ResultValidator results, InputHashService hashes, InferenceProperties properties) {
        this.requestValidator=requestValidator; this.ai=ai;
        this.results=results; this.hashes=hashes; this.properties=properties;
    }

    public InferenceResponse infer(InferenceRequest request) {
        return infer(request, false);
    }

    public InferenceResponse infer(InferenceRequest request, boolean testMode) {
        requestValidator.validate(request);
        String hash = hashes.hash(request);
        if (testMode) return simulate(request, hash);
        String source = "AI";
        MappingProposal proposal = ai.infer(request);
        List<String> errors = results.validateProposal(request, proposal);
        double confidence = proposal != null && ResultValidator.validConfidence(proposal.confidence()) ? proposal.confidence() : 0;
        if (proposal != null && proposal.mappings() != null) {
            for (MappingProposal.Entry entry : proposal.mappings()) {
                if (entry != null && ResultValidator.validConfidence(entry.confidence()))
                    confidence = Math.min(confidence, entry.confidence());
            }
        }
        if (proposal != null && Boolean.FALSE.equals(proposal.certain())
                && ResultValidator.validConfidence(proposal.confidence())
                && proposal.reason() != null && !proposal.reason().isBlank() && proposal.reason().length() <= 2000)
            return failure(request, confidence, proposal.reason(), errors, "REVIEW_REQUIRED", hash, source);
        if (!errors.isEmpty()) return failure(request, confidence, "결과 검증에 실패했습니다.",
                errors, "RESULT_VALIDATION_FAILED", hash, source);
        if (!Boolean.TRUE.equals(proposal.certain()) || confidence < properties.confidenceThreshold())
            return failure(request, confidence, proposal.reason(), List.of(), "REVIEW_REQUIRED", hash, source);

        List<PurchaseOptionItem> items = results.assemble(request, proposal);
        errors = results.validateItems(request, proposal, items);
        if (!errors.isEmpty()) return failure(request, confidence, "단품 결과 검증에 실패했습니다.",
                errors, "RESULT_VALIDATION_FAILED", hash, source);
        List<OptionMapping> mappings = optionMappings(request, proposal);
        return new InferenceResponse(request.goodsId(), true, true, confidence, mappings, items,
                proposal.reason(), List.of(), null, hash, properties.promptVersion(), source);
    }

    private InferenceResponse simulate(InferenceRequest request, String hash) {
        MappingProposal proposal = MockOptionInferenceService.infer(request);
        List<String> errors = results.validateProposal(request, proposal);
        if (!errors.isEmpty()) return failure(request, 0, "모의 결과 검증에 실패했습니다.",
                errors, "RESULT_VALIDATION_FAILED", hash, "TEST");
        List<PurchaseOptionItem> items = results.assemble(request, proposal);
        errors = results.validateItems(request, proposal, items);
        if (!errors.isEmpty()) return failure(request, 0, "모의 결과 검증에 실패했습니다.",
                errors, "RESULT_VALIDATION_FAILED", hash, "TEST");
        List<OptionMapping> mappings = optionMappings(request, proposal);
        return new InferenceResponse(request.goodsId(), false, false, 0, mappings, items,
                proposal.reason(), List.of(), "TEST_MODE", hash, properties.promptVersion(), "TEST");
    }

    private static List<OptionMapping> optionMappings(InferenceRequest request, MappingProposal proposal) {
        Map<String, String> original = new HashMap<>();
        for (SourceOption source : request.options()) original.put(source.optionId(), source.optionName1());
        return proposal.mappings().stream()
                .map(entry -> new OptionMapping(entry.optionId(), original.get(entry.optionId()),
                        entry.targetPurchaseOptionName(), entry.value(), entry.confidence())).toList();
    }

    private InferenceResponse failure(InferenceRequest r, double confidence, String reason,
            List<String> errors, String code, String hash, String source) {
        // 검증 실패나 낮은 신뢰도의 부분 매핑이 자동 적용되지 않게 결과 목록은 비운다.
        return new InferenceResponse(r.goodsId(), false, false, confidence, List.of(), List.of(),
                reason, errors, code, hash, properties.promptVersion(), source);
    }
}
