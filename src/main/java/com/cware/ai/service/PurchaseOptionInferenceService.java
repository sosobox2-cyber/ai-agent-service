package com.cware.ai.service;

import com.cware.ai.config.InferenceProperties;
import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.cware.ai.util.InputHashService;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
/** 요청 검증부터 추론, 최종 적용 판단, 진단 정보와 사용량 응답까지 전체 흐름을 조율한다. */
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
        // 추론 전에 요청 데이터의 유효성을 검증한다.
        requestValidator.validate(request);
        // 응답에 포함할 입력 해시를 생성한다.
        String hash = hashes.hash(request);
        // 테스트 모드에서는 실제 AI 호출 없이 모의 추론 결과를 반환한다.
        if (testMode) return simulate(request, hash);
        // AI 추론 과정에서 발생한 호출별 사용량을 수집한다.
        List<AiCallUsage> usage = new ArrayList<>();
        MappingProposal proposal = ai.infer(request, usage::add);
        proposal = PackCapacityNormalizer.normalize(request,
                ImplicitSingleQuantityPolicy.normalize(request, proposal));
        // 제안된 매핑을 검증하고 신뢰도를 평가한 응답에 AI 사용량을 포함한다.
        return assess(request, hash, proposal).withUsage(usage);
    }

    private InferenceResponse assess(InferenceRequest request, String hash, MappingProposal proposal) {
        // 응답의 추론 출처를 AI로 표시하고, 제안의 형식·단품 ID·허용 옵션명 등을 먼저 검증한다.
        String source = "AI";
        List<String> errors = results.validateAiProposal(request, proposal);
        // 전체 신뢰도가 유효하지 않으면 0으로 처리하고, 유효한 개별 매핑 신뢰도 중 최솟값을 반영한다.
        // 잘못된 개별 신뢰도는 최솟값 계산에서 제외하되, 위의 제안 검증에서 오류로 처리한다.
        double confidence = proposal != null && ResultValidator.validConfidence(proposal.confidence()) ? proposal.confidence() : 0;
        if (proposal != null && proposal.mappings() != null) {
            for (MappingProposal.Entry entry : proposal.mappings()) {
                if (entry != null && ResultValidator.validConfidence(entry.confidence()))
                    confidence = Math.min(confidence, entry.confidence());
            }
        }
        // AI가 불확실하다고 명시하고 신뢰도와 사유가 유효하면 검토 대상으로 우선 반환한다.
        // 제안 검증 오류가 함께 있어도 AI의 불확실 판정을 주된 보류 사유로 사용하고 오류를 함께 전달한다.
        if (proposal != null && Boolean.FALSE.equals(proposal.certain())
                && ResultValidator.validConfidence(proposal.confidence())
                && proposal.reason() != null && !proposal.reason().isBlank() && proposal.reason().length() <= 2000)
            return diagnose(failure(request, confidence, proposal.reason(), errors, "REVIEW_REQUIRED", hash, source),
                    proposal, "AI_UNCERTAIN", "AI가 certain=false로 판단하여 서버가 적용을 보류했습니다."
                            + (errors.isEmpty() ? "" : " 결과 검증 오류도 있습니다."));
        // 위의 불확실 판정에 해당하지 않는 제안은 검증 오류가 하나라도 있으면 적용하지 않는다.
        if (!errors.isEmpty()) return diagnose(failure(request, confidence, "결과 검증에 실패했습니다.",
                errors, "RESULT_VALIDATION_FAILED", hash, source), proposal,
                "RESULT_VALIDATION_FAILED", "AI 제안이 서버의 형식·단위·출처·계층 검증을 통과하지 못했습니다.");
        // 제안 검증을 통과해도 AI의 확실 판정과 최저 신뢰도 기준을 모두 만족해야 적용한다.
        if (!Boolean.TRUE.equals(proposal.certain()) || confidence < properties.confidenceThreshold())
            return diagnose(failure(request, confidence, proposal.reason(), List.of(), "REVIEW_REQUIRED", hash, source),
                    proposal, "LOW_CONFIDENCE", String.format(Locale.ROOT,
                            "전체 및 개별 매핑의 최저 신뢰도 %.2f가 서버 기준 %.2f보다 낮아 적용을 보류했습니다.",
                            confidence, properties.confidenceThreshold()));

        // 매핑을 단품별 구매옵션으로 조립한 뒤, 단품 ID·값·허용 옵션명·옵션 조합을 재검증한다.
        List<PurchaseOptionItem> items = results.assemble(request, proposal);
        errors = results.validateItems(request, proposal, items);
        if (!errors.isEmpty()) return diagnose(failure(request, confidence, "단품 결과 검증에 실패했습니다.",
                errors, "RESULT_VALIDATION_FAILED", hash, source), proposal,
                "RESULT_VALIDATION_FAILED", "조립한 단품 결과가 서버 검증을 통과하지 못했습니다.");
        // 모든 검증을 통과하면 원본 옵션명을 포함한 매핑과 단품 결과를 성공 응답으로 반환한다.
        // diagnose는 AI의 판단 내용과 서버의 승인 사유를 함께 기록한다.
        List<OptionMapping> mappings = optionMappings(request, proposal);
        return diagnose(new InferenceResponse(request.goodsId(), true, true, confidence, mappings, items,
                proposal.reason(), List.of(), null, hash, properties.promptVersion(), source),
                proposal, "ACCEPTED", "AI의 확실 판정, 신뢰도 기준 및 서버의 구조·대상별 근거 검증을 통과했습니다. 모든 원문 의미와 합산의 정확성을 보증하지는 않습니다.");
    }

    private InferenceResponse simulate(InferenceRequest request, String hash) {
        // 모의 결과도 구조·조합을 검증하지만 성공·자동 적용 후보로 승인하지 않는다.
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
        // 승인 여부와 별개로 AI 원본 제안을 보존하고 서버가 판단한 사유를 별도로 제공한다.
        AiAssessment assessment = proposal == null ? null : new AiAssessment(proposal.certain(),
                proposal.confidence(), proposal.mappings() == null ? null : proposal.mappings().stream()
                    .map(entry -> entry == null ? null : new AiAssessment.ProposedMapping(entry.optionId(),
                            entry.targetPurchaseOptionName(), entry.value(), entry.confidence(),
                            entry.evidenceSource(), entry.evidenceText(), entry.calculation(),
                            entry.serverNormalization())).toList(), proposal.reason(),
                proposal.quantityEvidence());
        return response.withAssessments(assessment,
                new ServerAssessment(decisionCode, serverReason, properties.confidenceThreshold()));
    }

    private static List<OptionMapping> optionMappings(InferenceRequest request, MappingProposal proposal) {
        // AI 제안의 단품 ID에 원본 옵션명을 연결하되 제안 값과 근거는 그대로 반환한다.
        Map<String, String> original = new HashMap<>();
        for (SourceOption source : request.options()) original.put(source.optionId(), source.optionName1());
        return proposal.mappings().stream()
                .map(entry -> new OptionMapping(entry.optionId(), original.get(entry.optionId()),
                        entry.targetPurchaseOptionName(), entry.value(), entry.confidence(),
                        entry.evidenceSource(), entry.evidenceText(), entry.calculation(),
                        entry.serverNormalization())).toList();
    }

    private InferenceResponse failure(InferenceRequest r, double confidence, String reason,
            List<String> errors, String code, String hash, String source) {
        // 검증 실패나 낮은 신뢰도의 부분 매핑이 자동 적용되지 않게 결과 목록은 비운다.
        return new InferenceResponse(r.goodsId(), false, false, confidence, List.of(), List.of(),
                reason, errors, code, hash, properties.promptVersion(), source);
    }
}
