package com.cware.ai.inference;

import com.cware.ai.config.AiRetryProperties;
import com.cware.ai.dto.InferenceRequest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import static com.cware.ai.inference.AiRetryReason.*;

@Component
/** 검증 실패를 재시도 사유로 변환하고 최종 적용 신뢰도 충족 여부도 제공한다. */
public class AiInferenceValidator {
    private static final Logger LOG = LoggerFactory.getLogger(AiInferenceValidator.class);
    private final ResultValidator results;
    private final AiRetryProperties retry;
    private final double applicationConfidenceThreshold;
    private final boolean coverageCheckEnabled;

    public AiInferenceValidator(ResultValidator results, AiRetryProperties retry) {
        this(results, retry, .80, true);
    }

    @Autowired
    public AiInferenceValidator(ResultValidator results, AiRetryProperties retry,
            @Value("${app.inference.confidence-threshold:0.80}") double applicationConfidenceThreshold,
            @Value("${app.inference.coverage-check-enabled:true}") boolean coverageCheckEnabled) {
        this.results = results;
        this.retry = retry;
        this.applicationConfidenceThreshold = applicationConfidenceThreshold;
        this.coverageCheckEnabled = coverageCheckEnabled;
    }

    public AiInferenceValidationResult validate(InferenceRequest request, MappingProposal proposal) {
        // 단품 누락·중복과 허용되지 않은 이름을 재시도 사유별로 수집한다.
        var errors = new ArrayList<AiInferenceValidationResult.ValidationError>();
        if (proposal == null) {
            errors.add(error(INVALID_RESPONSE, "AI 응답이 없습니다."));
            return new AiInferenceValidationResult(errors);
        }
        if (Boolean.FALSE.equals(proposal.certain())) errors.add(error(CERTAIN_FALSE, "certain=false"));
        Set<String> ids = new HashSet<>(), returned = new HashSet<>();
        Set<List<String>> seen = new HashSet<>();
        request.options().forEach(option -> ids.add(option.optionId()));
        if (proposal.mappings() != null) for (var entry : proposal.mappings()) {
            if (entry == null) { errors.add(error(EMPTY_VALUE, "mapping이 null입니다.")); continue; }
            if (blank(entry.optionId()) || blank(entry.targetPurchaseOptionName()) || blank(entry.value()))
                errors.add(error(EMPTY_VALUE, "optionId, targetPurchaseOptionName, value는 필수입니다."));
            returned.add(entry.optionId());
            if (!blank(entry.optionId()) && !ids.contains(entry.optionId()))
                errors.add(error(UNKNOWN_OPTION_ID, "입력에 없는 optionId: " + entry.optionId()));
            if (!blank(entry.targetPurchaseOptionName()) && !request.allowedPurchaseOptions().contains(entry.targetPurchaseOptionName()))
                errors.add(error(INVALID_PURCHASE_OPTION, "허용되지 않은 구매옵션: " + entry.targetPurchaseOptionName()));
            if (!blank(entry.optionId()) && !blank(entry.targetPurchaseOptionName())
                    && !seen.add(List.of(entry.optionId(), entry.targetPurchaseOptionName())))
                errors.add(error(DUPLICATE_MAPPING, "동일한 단품·구매옵션 중복 매핑"));
        }
        ids.stream().filter(id -> !returned.contains(id)).sorted()
                .forEach(id -> errors.add(error(MISSING_OPTION_MAPPING, "누락된 optionId: " + id)));
        checkCoverage(request, proposal, ids, errors);
        // 기존 구조·단위 검증을 재사용하고, 구조가 유효하면 조립한 단품 조합도 확인한다.
        List<String> structural = results.validateProposal(request, proposal);
        structural.forEach(message -> errors.add(error(INVALID_RESPONSE, message)));
        if (structural.isEmpty()) results.validateItems(request, proposal, results.assemble(request, proposal))
                .forEach(message -> errors.add(error(INVALID_RESPONSE, message)));
        // 선택적으로 켠 재추론 신뢰도 기준은 최종 자동 적용 신뢰도 기준과 별도로 평가한다.
        if (retry.confidenceThresholdEnabled() && ResultValidator.validConfidence(proposal.confidence())) {
            double minimum = proposal.confidence();
            if (proposal.mappings() != null) for (var entry : proposal.mappings())
                if (entry != null && ResultValidator.validConfidence(entry.confidence())) minimum = Math.min(minimum, entry.confidence());
            if (minimum < retry.confidenceThreshold()) errors.add(error(LOW_CONFIDENCE, "재추론 신뢰도 기준 미달"));
        }
        return new AiInferenceValidationResult(errors);
    }

    /**
     * 허용 구매옵션별로 어떤 단품이 매핑을 받았는지 확인한다.
     * 일부 단품에만 값이 있으면 근거 부재가 아니라 누락일 가능성이 높으므로 검증 오류로 올린다.
     * 모든 단품에서 빠진 구매옵션은 원문에 근거가 없어 정당할 수 있으므로 기록만 남긴다.
     */
    private void checkCoverage(InferenceRequest request, MappingProposal proposal,
            Set<String> ids, List<AiInferenceValidationResult.ValidationError> errors) {
        if (ids.isEmpty()) return;
        Map<String, Set<String>> covered = new LinkedHashMap<>();
        if (proposal.mappings() != null) for (var entry : proposal.mappings())
            if (entry != null && !blank(entry.optionId()) && !blank(entry.targetPurchaseOptionName())
                    && ids.contains(entry.optionId()))
                covered.computeIfAbsent(entry.targetPurchaseOptionName(), key -> new HashSet<>()).add(entry.optionId());
        var uncovered = new ArrayList<String>();
        for (String name : request.allowedPurchaseOptions()) {
            Set<String> mapped = covered.get(name);
            if (mapped == null || mapped.isEmpty()) { uncovered.add(name); continue; }
            if (!coverageCheckEnabled || mapped.size() >= ids.size()) continue;
            var missing = ids.stream().filter(id -> !mapped.contains(id)).sorted().toList();
            errors.add(error(INCONSISTENT_OPTION_COVERAGE,
                    "구매옵션 " + name + "이 일부 단품에만 매핑되었습니다. 누락된 optionId: " + missing));
        }
        // 전량 누락은 자동 적용을 막지 않으므로, 조용히 사라지지 않도록 관측 가능하게 남긴다.
        if (!uncovered.isEmpty() && Boolean.TRUE.equals(proposal.certain()))
            LOG.info("ai_option_coverage goods_id={} certain=true uncovered_purchase_options={}",
                    request.goodsId(), uncovered);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    /** 최종 적용 신뢰도를 확인한다. 이 검사 자체가 추가 AI 호출을 발생시키지는 않는다. */
    public boolean meetsApplicationConfidence(MappingProposal proposal) {
        return proposal != null && ResultValidator.validConfidence(proposal.confidence())
                && proposal.confidence() >= applicationConfidenceThreshold && proposal.mappings() != null
                && proposal.mappings().stream().allMatch(entry -> entry != null
                    && ResultValidator.validConfidence(entry.confidence())
                    && entry.confidence() >= applicationConfidenceThreshold);
    }
    private static AiInferenceValidationResult.ValidationError error(AiRetryReason code, String message) {
        return new AiInferenceValidationResult.ValidationError(code, message);
    }
}
