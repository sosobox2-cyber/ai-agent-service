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
        List<AiCallUsage> usage = new ArrayList<>();
        MappingProposal proposal = ai.infer(request, usage::add);
        return assess(request, hash, proposal).withUsage(usage);
    }

    private InferenceResponse assess(InferenceRequest request, String hash, MappingProposal proposal) {
        String source = "AI";
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
            return diagnose(failure(request, confidence, proposal.reason(), errors, "REVIEW_REQUIRED", hash, source),
                    proposal, "AI_UNCERTAIN", "AI가 certain=false로 판단하여 서버가 적용을 보류했습니다."
                            + (errors.isEmpty() ? "" : " 결과 검증 오류도 있습니다."));
        if (!errors.isEmpty()) return diagnose(failure(request, confidence, "결과 검증에 실패했습니다.",
                errors, "RESULT_VALIDATION_FAILED", hash, source), proposal,
                "RESULT_VALIDATION_FAILED", "AI 제안이 서버의 응답 형식·단품 ID·허용 옵션명 검증을 통과하지 못했습니다.");
        if (!Boolean.TRUE.equals(proposal.certain()) || confidence < properties.confidenceThreshold())
            return diagnose(failure(request, confidence, proposal.reason(), List.of(), "REVIEW_REQUIRED", hash, source),
                    proposal, "LOW_CONFIDENCE", String.format(Locale.ROOT,
                            "전체 및 개별 매핑의 최저 신뢰도 %.2f가 서버 기준 %.2f보다 낮아 적용을 보류했습니다.",
                            confidence, properties.confidenceThreshold()));

        List<PurchaseOptionItem> items = results.assemble(request, proposal);
        errors = results.validateItems(request, proposal, items);
        if (!errors.isEmpty()) return diagnose(failure(request, confidence, "단품 결과 검증에 실패했습니다.",
                errors, "RESULT_VALIDATION_FAILED", hash, source), proposal,
                "RESULT_VALIDATION_FAILED", "조립한 단품 결과가 서버 검증을 통과하지 못했습니다.");
        List<OptionMapping> mappings = optionMappings(request, proposal);
        return diagnose(new InferenceResponse(request.goodsId(), true, true, confidence, mappings, items,
                proposal.reason(), List.of(), null, hash, properties.promptVersion(), source),
                proposal, "ACCEPTED", "AI의 확실 판정, 신뢰도 기준 및 서버의 기본 구조 검증을 통과했습니다. 값과 근거는 AI 판단을 사용합니다.");
    }

    private InferenceResponse simulate(InferenceRequest request, String hash) {
        MappingProposal proposal = MockOptionInferenceService.infer(request);
        List<String> errors = results.validateProposal(request, proposal);
        if (!errors.isEmpty()) return diagnose(failure(request, 0, "모의 결과 검증에 실패했습니다.",
                errors, "RESULT_VALIDATION_FAILED", hash, "TEST"), null,
                "RESULT_VALIDATION_FAILED", "모의 추출 결과가 서버 검증을 통과하지 못했습니다.");
        List<PurchaseOptionItem> items = results.assemble(request, proposal);
        errors = results.validateItems(request, proposal, items);
        if (!errors.isEmpty()) return diagnose(failure(request, 0, "모의 결과 검증에 실패했습니다.",
                errors, "RESULT_VALIDATION_FAILED", hash, "TEST"), null,
                "RESULT_VALIDATION_FAILED", "조립한 모의 단품 결과가 서버 검증을 통과하지 못했습니다.");
        List<OptionMapping> mappings = optionMappings(request, proposal);
        return diagnose(new InferenceResponse(request.goodsId(), false, false, 0, mappings, items,
                proposal.reason(), List.of(), "TEST_MODE", hash, properties.promptVersion(), "TEST"),
                null, "TEST_MODE", "모의 추출 검증은 통과했지만 테스트 모드 결과는 자동 적용하지 않습니다.");
    }

    private InferenceResponse diagnose(InferenceResponse response, MappingProposal proposal,
            String decisionCode, String serverReason) {
        AiAssessment assessment = proposal == null ? null : new AiAssessment(proposal.certain(),
                proposal.confidence(), proposal.mappings() == null ? null : proposal.mappings().stream()
                    .map(entry -> entry == null ? null : new AiAssessment.ProposedMapping(entry.optionId(),
                            entry.targetPurchaseOptionName(), entry.value(), entry.confidence(),
                            entry.evidenceSource(), entry.evidenceText(), entry.calculation())).toList(), proposal.reason());
        return response.withAssessments(assessment,
                new ServerAssessment(decisionCode, serverReason, properties.confidenceThreshold()));
    }

    private static List<OptionMapping> optionMappings(InferenceRequest request, MappingProposal proposal) {
        Map<String, String> original = new HashMap<>();
        for (SourceOption source : request.options()) original.put(source.optionId(), source.optionName1());
        return proposal.mappings().stream()
                .map(entry -> new OptionMapping(entry.optionId(), original.get(entry.optionId()),
                        entry.targetPurchaseOptionName(), entry.value(), entry.confidence(),
                        entry.evidenceSource(), entry.evidenceText(), entry.calculation())).toList();
    }

    private InferenceResponse failure(InferenceRequest r, double confidence, String reason,
            List<String> errors, String code, String hash, String source) {
        // 검증 실패나 낮은 신뢰도의 부분 매핑이 자동 적용되지 않게 결과 목록은 비운다.
        return new InferenceResponse(r.goodsId(), false, false, confidence, List.of(), List.of(),
                reason, errors, code, hash, properties.promptVersion(), source);
    }
}
