package com.cware.ai.inference;

import com.cware.ai.config.AiRetryProperties;
import com.cware.ai.dto.InferenceRequest;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import static com.cware.ai.inference.AiRetryReason.*;

@Component
public class AiInferenceValidator {
    private final ResultValidator results;
    private final AiRetryProperties retry;
    private final double applicationConfidenceThreshold;

    public AiInferenceValidator(ResultValidator results, AiRetryProperties retry) {
        this(results, retry, .80);
    }

    @Autowired
    public AiInferenceValidator(ResultValidator results, AiRetryProperties retry,
            @Value("${app.inference.confidence-threshold:0.80}") double applicationConfidenceThreshold) {
        this.results = results;
        this.retry = retry;
        this.applicationConfidenceThreshold = applicationConfidenceThreshold;
    }

    public AiInferenceValidationResult validate(InferenceRequest request, MappingProposal proposal) {
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
        // Reuse all existing structural/unit validation, including assembled option combinations.
        List<String> structural = results.validateProposal(request, proposal);
        structural.forEach(message -> errors.add(error(INVALID_RESPONSE, message)));
        if (structural.isEmpty()) results.validateItems(request, proposal, results.assemble(request, proposal))
                .forEach(message -> errors.add(error(INVALID_RESPONSE, message)));
        if (retry.confidenceThresholdEnabled() && ResultValidator.validConfidence(proposal.confidence())) {
            double minimum = proposal.confidence();
            if (proposal.mappings() != null) for (var entry : proposal.mappings())
                if (entry != null && ResultValidator.validConfidence(entry.confidence())) minimum = Math.min(minimum, entry.confidence());
            if (minimum < retry.confidenceThreshold()) errors.add(error(LOW_CONFIDENCE, "재추론 신뢰도 기준 미달"));
        }
        return new AiInferenceValidationResult(errors);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    /** Final approval remains separate from validation failover, so low confidence does not add a call. */
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
