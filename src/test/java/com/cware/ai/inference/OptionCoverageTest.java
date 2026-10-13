package com.cware.ai.inference;

import com.cware.ai.config.AiRetryProperties;
import com.cware.ai.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 허용 구매옵션이 일부 단품에만 매핑된 누락을 검증 오류로 올리고 전량 누락은 통과시키는지 검증한다. */
class OptionCoverageTest {
    private final InferenceRequest request = new InferenceRequest("68535109", "니트", "의류",
            List.of("색상", "패션의류/잡화 사이즈"), List.of(new SourceOption("1", "블랙/90"),
            new SourceOption("2", "블랙/95"), new SourceOption("3", "블랙/100"),
            new SourceOption("4", "블랙/105")), "제조국: 한국");

    private AiInferenceValidator validator(boolean coverageCheckEnabled) {
        return new AiInferenceValidator(new ResultValidator(), AiRetryProperties.defaults(), .80, coverageCheckEnabled);
    }

    /** 모든 단품에 색상과 사이즈를 채운 정상 응답을 만든다. */
    private List<MappingProposal.Entry> full() {
        var entries = new ArrayList<MappingProposal.Entry>();
        for (int i = 1; i <= 4; i++) {
            entries.add(new MappingProposal.Entry("" + i, "색상", "블랙", .95));
            entries.add(new MappingProposal.Entry("" + i, "패션의류/잡화 사이즈", "" + (85 + i * 5), .95));
        }
        return entries;
    }

    @Test void fullCoverageHasNoCoverageError() {
        var result = validator(true).validate(request, new MappingProposal(true, .95, full(), "전체 추출"));
        assertThat(result.reasons()).doesNotContain(AiRetryReason.INCONSISTENT_OPTION_COVERAGE);
    }

    @Test void sizeMissingOnlyForSomeOptionsIsReportedEvenWhenCertainAndAllIdsPresent() {
        // 단품 4의 사이즈만 빼면 모든 optionId는 색상으로 등장하므로 기존 누락 검사는 통과한다.
        var partial = full().stream()
                .filter(entry -> !("4".equals(entry.optionId())
                        && "패션의류/잡화 사이즈".equals(entry.targetPurchaseOptionName())))
                .toList();
        var result = validator(true).validate(request, new MappingProposal(true, .95, partial, "일부 추출"));
        assertThat(result.reasons()).doesNotContain(AiRetryReason.MISSING_OPTION_MAPPING);
        assertThat(result.reasons()).contains(AiRetryReason.INCONSISTENT_OPTION_COVERAGE);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.message()).contains("패션의류/잡화 사이즈").contains("4"));
    }

    @Test void coverageCheckCanBeDisabled() {
        var partial = full().stream()
                .filter(entry -> !("4".equals(entry.optionId())
                        && "패션의류/잡화 사이즈".equals(entry.targetPurchaseOptionName())))
                .toList();
        assertThat(validator(false).validate(request, new MappingProposal(true, .95, partial, "일부 추출")).reasons())
                .doesNotContain(AiRetryReason.INCONSISTENT_OPTION_COVERAGE);
    }

    /** 모든 단품에서 빠진 구매옵션은 근거가 없어 정당할 수 있으므로 검증을 막지 않는다. */
    @Test void purchaseOptionMissingFromEveryOptionIsNotTreatedAsCoverageError() {
        var colorsOnly = full().stream()
                .filter(entry -> "색상".equals(entry.targetPurchaseOptionName())).toList();
        var result = validator(true).validate(request, new MappingProposal(true, .95, colorsOnly, "색상만 추출"));
        assertThat(result.reasons()).doesNotContain(AiRetryReason.INCONSISTENT_OPTION_COVERAGE);
        assertThat(result.reasons()).doesNotContain(AiRetryReason.MISSING_OPTION_MAPPING);
    }
}
