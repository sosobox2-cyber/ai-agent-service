package com.cware.ai.evaluation;

import com.cware.ai.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static com.cware.ai.evaluation.EvaluationModels.*;
import static org.assertj.core.api.Assertions.*;

class ProductionSampleRecorderTest {
    @TempDir Path dir;

    private InferenceRequest request() {
        return new InferenceRequest("g1", "상품", "분류", List.of("색상", "사이즈"),
                List.of(new SourceOption("001", "블랙/55")), "정보", List.of());
    }
    private InferenceResponse response(boolean success, boolean auto, String decision, String source) {
        var mapping = new OptionMapping("001", "블랙/55", "색상", "블랙", .9, "optionName1", "블랙", null);
        return new InferenceResponse("g1", success, auto, .9, success ? List.of(mapping) : List.of(), List.of(), "이유",
                List.of(), null, "hash", "v45", source)
                .withAssessments(null, new ServerAssessment(decision, "사유", .8));
    }
    private ProductionSampleRecorder recorder(EvaluationStore store, double draw, Clock clock) {
        return new ProductionSampleRecorder(store, true, .10, .05, 90, clock, () -> draw);
    }
    private static Clock at(String day) { return Clock.fixed(Instant.parse(day + "T03:00:00Z"), ZoneId.of("Asia/Seoul")); }

    @Test void samplesAutoAppliedAndHeldAtTheirRatesOnly() throws Exception {
        var store = new EvaluationStore(dir.toString(), new ObjectMapper());
        // 추첨값 0.07: 자동 적용(10%)은 선택, 보류(5%)는 제외.
        var r = recorder(store, .07, at("2026-10-29"));
        r.offer(request(), response(true, true, "ACCEPTED", "AI"));
        r.offer(request(), response(true, false, "ACCEPTED_REVIEW_POLICY", "AI"));
        r.offer(request(), response(false, false, "RESULT_VALIDATION_FAILED", "AI"));
        r.offer(request(), response(false, false, "TEST_MODE", "TEST"));
        var datasets = store.datasets();
        assertThat(datasets).singleElement().satisfies(d -> assertThat(d.sourceHash()).isEqualTo("production-sample"));
        var data = store.data(datasets.get(0).id());
        assertThat(data.cases).hasSize(1);
        var test = data.cases.values().iterator().next();
        assertThat(test.id()).startsWith("p20261029-");
        assertThat(test.references()).extracting(Reference::target).containsExactly("색상", "사이즈");
        assertThat(test.references()).allMatch(ref -> ref.baseline().isEmpty());
        var run = data.latest.get(test.id());
        assertThat(run.response().autoApplyCandidate()).isTrue();
        assertThat(run.comparisons()).extracting(Comparison::actual).containsExactly("블랙", null);

        // 추첨값 0.03이면 보류 결과도 같은 데이터셋에 쌓인다.
        recorder(store, .03, at("2026-10-29")).offer(request(), response(true, false, "ACCEPTED_REVIEW_POLICY", "AI"));
        assertThat(store.datasets()).hasSize(1);
        assertThat(store.data(datasets.get(0).id()).cases).hasSize(2);
    }

    @Test void prunesSamplesOlderThanRetentionAndKeepsDatasetAfterRestoreIdChange() throws Exception {
        var store = new EvaluationStore(dir.toString(), new ObjectMapper());
        recorder(store, 0, at("2026-07-01")).offer(request(), response(true, true, "ACCEPTED", "AI"));
        recorder(store, 0, at("2026-09-30")).offer(request(), response(true, true, "ACCEPTED", "AI"));
        // 당일 포함 90일 보관: 2026-10-29 기준 2026-08-01 이전 표본은 지운다.
        recorder(store, 0, at("2026-10-29")).offer(request(), response(true, true, "ACCEPTED", "AI"));
        var id = store.datasets().get(0).id();
        assertThat(store.data(id).cases.keySet()).noneMatch(c -> c.startsWith("p20260701"))
                .anyMatch(c -> c.startsWith("p20260930")).anyMatch(c -> c.startsWith("p20261029"));
        assertThat(ProductionSampleRecorder.day("c00001")).isEqualTo(LocalDate.MAX);
    }

    @Test void keepsAllSamplesWhenRetentionIsUnlimited() throws Exception {
        var store = new EvaluationStore(dir.toString(), new ObjectMapper());
        for (String day : List.of("2026-07-01", "2026-10-29"))
            new ProductionSampleRecorder(store, true, .10, .05, 0, at(day), () -> 0).offer(request(), response(true, true, "ACCEPTED", "AI"));
        assertThat(store.data(store.datasets().get(0).id()).cases.keySet())
                .anyMatch(c -> c.startsWith("p20260701")).anyMatch(c -> c.startsWith("p20261029"));
        assertThatThrownBy(() -> new ProductionSampleRecorder(store, true, .10, .05, -1, at("2026-10-29"), () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void storageFailureDoesNotThrow() throws Exception {
        var store = new EvaluationStore(dir.toString(), new ObjectMapper()) {
            @Override synchronized void appendCase(Dataset dataset, Case test, Run run) { throw new IllegalStateException("디스크"); }
        };
        assertThatCode(() -> recorder(store, 0, at("2026-10-29")).offer(request(), response(true, true, "ACCEPTED", "AI")))
                .doesNotThrowAnyException();
    }

    @Test void updatesDatasetCountsAfterAppendAndPrune() throws Exception {
        var store = new EvaluationStore(dir.toString(), new ObjectMapper());
        recorder(store, 0, at("2026-07-01")).offer(request(), response(true, true, "ACCEPTED", "AI"));
        recorder(store, 0, at("2026-10-29")).offer(request(), response(true, true, "ACCEPTED", "AI"));
        var dataset = store.datasets().get(0);
        assertThat(dataset.products()).isEqualTo(1);
        assertThat(dataset.rows()).isEqualTo(2);
        assertThat(dataset.options()).isEqualTo(1);
        assertThat(store.gitSnapshot(dataset.id()).source()).isEqualTo(dataset);
        store.prune(dataset.id(), ignored -> false);
        assertThat(store.datasets().get(0).products()).isZero();
        assertThat(store.datasets().get(0).rows()).isZero();
    }

    @Test void restoresSampleReviewsAndContinuesRecordingUnderNewDatasetId() throws Exception {
        var mapper = new ObjectMapper();
        var store = new EvaluationStore(dir.resolve("original").toString(), mapper);
        recorder(store, 0, at("2026-10-29")).offer(request(), response(true, true, "ACCEPTED", "AI"));
        var dataset = store.datasets().get(0);
        var data = store.data(dataset.id());
        var caseId = data.cases.keySet().iterator().next();
        var run = data.latest.get(caseId);
        new EvaluationService(store, null).review(dataset.id(), caseId, 1,
                new ReviewRequest("SUCCESS", "fixture", "", run.id()));
        var bytes = new java.io.ByteArrayOutputStream();
        store.backup(dataset.id(), bytes);
        var restored = new EvaluationStore(dir.resolve("restored").toString(), mapper);
        String newId = UUID.randomUUID().toString();
        restored.restore(EvaluationArchive.read(bytes.toByteArray(), mapper, newId, Instant.now().toString()));
        assertThat(restored.gitSnapshot(newId).cases()).isEqualTo(store.gitSnapshot(dataset.id()).cases());
        recorder(restored, 0, at("2026-10-29")).offer(request(), response(true, false, "ACCEPTED_REVIEW_POLICY", "AI"));
        assertThat(restored.datasets()).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(newId);
            assertThat(d.products()).isEqualTo(2);
        });
        restored.prune(newId, ignored -> false);
        bytes.reset();
        restored.backup(newId, bytes);
        assertThat(EvaluationArchive.read(bytes.toByteArray(), mapper, UUID.randomUUID().toString(),
                Instant.now().toString()).cases()).isEmpty();
    }
}
