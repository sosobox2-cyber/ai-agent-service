package com.cware.ai.evaluation;

import com.cware.ai.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.DoubleSupplier;
import static com.cware.ai.evaluation.EvaluationModels.*;

/**
 * 운영 추론 결과 일부를 평가 데이터셋 "운영 표본"에 쌓는다. 사람이 기존 검토 화면에서 정답을 확정하면
 * 자동 적용 결과의 실제 오승인율을 비용 없이 잴 수 있다(2026-10-29 사용자 결정: 자동 적용 10%, 보류 5%, 보관 기한 없음. retention-days를 양수로 두면 그 일수만 보관).
 * 저장 실패는 API 응답에 영향을 주지 않는다. 파일 저장소에서만 동작한다.
 */
@Component
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
@ConditionalOnExpression("'${app.evaluation.storage:file}' == 'file'")
public class ProductionSampleRecorder {
    private static final Logger LOG = LoggerFactory.getLogger(ProductionSampleRecorder.class);
    /** 배포 복원 후 ID가 바뀌어도 같은 데이터셋을 찾도록 sourceHash로 구분한다. */
    static final String SOURCE = "production-sample";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final EvaluationStore store;
    private final boolean enabled;
    private final double autoRate, heldRate;
    private final int retentionDays;
    private final Clock clock;
    private final DoubleSupplier random;
    private LocalDate prunedOn;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionSampleRecorder(EvaluationStore store,
            @Value("${app.evaluation.production-sample.enabled:true}") boolean enabled,
            @Value("${app.evaluation.production-sample.auto-apply-rate:0.10}") double autoRate,
            @Value("${app.evaluation.production-sample.review-policy-rate:0.05}") double heldRate,
            @Value("${app.evaluation.production-sample.retention-days:0}") int retentionDays) {
        this(store, enabled, autoRate, heldRate, retentionDays, Clock.system(ZoneId.of("Asia/Seoul")),
                () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    ProductionSampleRecorder(EvaluationStore store, boolean enabled, double autoRate, double heldRate,
            int retentionDays, Clock clock, DoubleSupplier random) {
        if (!(autoRate >= 0 && autoRate <= 1 && heldRate >= 0 && heldRate <= 1) || retentionDays < 0)
            throw new IllegalArgumentException("운영 표본 비율은 0~1, 보관 기간은 0(기한 없음) 이상이어야 합니다.");
        this.store = store; this.enabled = enabled; this.autoRate = autoRate; this.heldRate = heldRate;
        this.retentionDays = retentionDays; this.clock = clock; this.random = random;
    }

    /** 실제 AI의 승인 결과만 표본 후보다. 자동 적용과 정책 보류를 다른 비율로 고른다. */
    public void offer(InferenceRequest request, InferenceResponse response) {
        if (!enabled || request == null || response == null || !"AI".equals(response.inferenceSource()) || !response.success()) return;
        double rate = response.autoApplyCandidate() ? autoRate
                : response.serverAssessment() != null && "ACCEPTED_REVIEW_POLICY".equals(response.serverAssessment().decisionCode()) ? heldRate : 0;
        if (rate <= 0 || random.getAsDouble() >= rate) return;
        try { record(request, response); }
        catch (Exception e) { LOG.warn("production_sample_failed goods_id={} reason={}", request.goodsId(), e.toString()); }
    }

    synchronized void record(InferenceRequest request, InferenceResponse response) throws Exception {
        LocalDate today = LocalDate.now(clock);
        Dataset dataset = dataset();
        // 보관 기간 0은 기한 없음이다. 양수로 설정하면 그 일수가 지난 표본을 하루 한 번 지운다.
        if (retentionDays > 0 && !today.equals(prunedOn)) {
            LocalDate oldest = today.minusDays(retentionDays - 1L);
            int removed = store.datasets().stream().anyMatch(d -> d.id().equals(dataset.id()))
                    ? store.prune(dataset.id(), id -> !day(id).isBefore(oldest)) : 0;
            if (removed > 0) LOG.info("production_sample_pruned count={} before={}", removed, oldest);
            prunedOn = today;
        }
        String caseId = "p" + today.format(DAY) + "-" + UUID.randomUUID().toString().substring(0, 8);
        // 허용 구매옵션 × 단품마다 기준 행을 만든다. 기준값은 비워 두고 사람이 검토에서 확정값을 넣는다.
        List<Reference> references = new ArrayList<>();
        int row = 1;
        for (SourceOption option : request.options())
            for (String target : request.allowedPurchaseOptions())
                references.add(new Reference(row++, option.optionId(), target, "", option.optionName1(), "운영 표본", List.of()));
        Case test = new Case(caseId, request, references, List.of());
        String now = Instant.now(clock).toString();
        Run run = new Run(UUID.randomUUID().toString(), caseId, now, now, "AI", null, response,
                EvaluationService.compare(test, response, Map.of()), request);
        store.appendCase(dataset, test, run);
    }

    private Dataset dataset() throws Exception {
        return store.datasets().stream().filter(d -> SOURCE.equals(d.sourceHash())).findFirst()
                .orElseGet(() -> new Dataset(UUID.randomUUID().toString(), "운영 표본", SOURCE, Instant.now(clock).toString(), 0, 0, 0, 0));
    }

    /** 사례 ID p20261029-xxxx의 날짜. 형식이 다르면 지우지 않도록 먼 미래로 본다. */
    static LocalDate day(String caseId) {
        try { return LocalDate.parse(caseId.substring(1, 9), DAY); }
        catch (RuntimeException e) { return LocalDate.MAX; }
    }
}
