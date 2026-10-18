package com.cware.ai.evaluation;

import com.cware.ai.dto.*;
import com.cware.ai.exception.InferenceException;
import com.cware.ai.service.PurchaseOptionInferenceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import static com.cware.ai.evaluation.EvaluationModels.*;

@Service
@ConditionalOnProperty(name="app.evaluation.enabled", havingValue="true")
public class EvaluationService {
    private static final Logger LOG = LoggerFactory.getLogger(EvaluationService.class);
    private final EvaluationStore store;
    private final PurchaseOptionInferenceService inference;
    public EvaluationService(EvaluationStore store, PurchaseOptionInferenceService inference) {
        this.store = store; this.inference = inference;
    }
    public Page page(String datasetId, String query, String state, String target, int page, int size) throws IOException {
        var data = store.data(datasetId);
        int pageSize = Math.max(1, Math.min(size, 100));
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        Map<String, Long> counts = new LinkedHashMap<>();
        List<Row> filtered = new ArrayList<>();
        for (Case test : data.cases.values()) {
            Row row = summarize(test, data.latest.get(test.id()), data.reviews.getOrDefault(test.id(), Map.of()));
            counts.merge(row.state(), 1L, Long::sum);
            counts.merge("CONFIRMED_ROWS", (long) row.confirmed(), Long::sum);
            if ((!needle.isEmpty() && !(row.goodsId() + " " + row.goodsName() + " " + row.categoryName()).toLowerCase(Locale.ROOT).contains(needle))
                    || (state != null && !state.isBlank() && !state.equals(row.state()))
                    || (target != null && !target.isBlank() && test.references().stream().noneMatch(r -> r.target().equals(target)))) continue;
            filtered.add(row);
        }
        counts.put("TOTAL", (long) data.cases.size());
        int current = Math.max(1, page), from = (int) Math.min(filtered.size(), (long) (current - 1) * pageSize);
        return new Page(filtered.subList(from, Math.min(filtered.size(), from + pageSize)), filtered.size(), current, pageSize, counts);
    }
    public Detail detail(String datasetId, String caseId) throws IOException {
        var data = store.data(datasetId);
        synchronized (data.lock(caseId)) {
            Case test = store.test(data, caseId);
            return new Detail(test, data.latest.get(caseId), store.history(datasetId, caseId), data.reviews.getOrDefault(caseId, Map.of()));
        }
    }
    public Run run(String datasetId, String caseId, RunRequest command) throws IOException {
        if (command == null || !Set.of("MOCK", "AI").contains(Objects.toString(command.mode(), ""))) throw invalid("실행 모드를 확인하세요.");
        if (command.mode().equals("AI") && !command.allowPaidCall()) throw invalid("실제 AI 호출의 비용 발생에 동의해야 합니다.");
        var data = store.data(datasetId);
        synchronized (data.lock(caseId)) {
            Case test = store.test(data, caseId);
            if (!test.issues().isEmpty()) throw invalid("입력 데이터 확인이 필요한 사례입니다: " + String.join(" / ", test.issues()));
            String start = Instant.now().toString(), error = null;
            InferenceResponse response = null;
            try { response = inference.infer(test.request(), command.mode().equals("MOCK")); }
            catch (InferenceException e) { error = e.code() + ": " + e.getMessage(); }
            catch (RuntimeException e) {
                LOG.error("평가 추론 처리 실패 (caseId={})", caseId, e);
                error = "추론 처리 실패. 서버 로그와 입력을 확인하세요.";
            }
            List<Comparison> comparisons = compare(test, response, data.reviews.getOrDefault(caseId, Map.of()));
            Run run = new Run(UUID.randomUUID().toString(), caseId, start, Instant.now().toString(), command.mode(), error, response, comparisons);
            store.saveRun(datasetId, data, run);
            return run;
        }
    }
    public Review review(String datasetId, String caseId, int rowNumber, ReviewRequest command) throws IOException {
        if (command == null || !Set.of("UNREVIEWED", "SUCCESS", "NEEDS_FIX", "REFERENCE_ISSUE").contains(Objects.toString(command.status(), "")))
            throw invalid("검토 상태를 확인하세요.");
        String note = command.note() == null ? "" : command.note().strip();
        String correction = command.confirmedValue() == null ? "" : command.confirmedValue().strip();
        if (note.length() > 2000 || correction.length() > 500) throw invalid("메모 또는 확정값이 너무 깁니다.");
        var data = store.data(datasetId);
        synchronized (data.lock(caseId)) {
            Case test = store.test(data, caseId);
            if (test.references().stream().noneMatch(r -> r.row() == rowNumber)) throw invalid("해당 기준 행이 없습니다.");
            Run run = data.latest.get(caseId);
            if (run != null && !run.id().equals(command.runId()))
                throw new InferenceException("EVALUATION_STALE_REVIEW", HttpStatus.CONFLICT, "새 실행 결과가 있습니다. 상세를 새로 열고 검토하세요.");
            Comparison result = run == null ? null : run.comparisons().stream().filter(c -> c.row() == rowNumber).findFirst().orElse(null);
            Map<Integer, Review> entries = new LinkedHashMap<>(data.reviews.getOrDefault(caseId, Map.of()));
            Review previous = entries.get(rowNumber);
            if (command.status().equals("SUCCESS")) {
                if (run == null || !run.mode().equals("AI") || run.response() == null || !run.response().success()
                        || run.error() != null || result == null || result.actual() == null || result.actual().isBlank()
                        || result.status().equals("DUPLICATE")) throw invalid("서버가 승인한 실제 AI 결과만 성공으로 확인할 수 있습니다. 모의 결과는 성공 처리할 수 없습니다.");
                correction = result.actual();
            } else if (!correction.isBlank() && !correction.equals(previous == null ? "" : previous.confirmedValue()) && note.isBlank())
                throw invalid("기준값을 교정할 때는 검토 메모를 입력하세요.");
            if (correction.isBlank() && previous != null && !command.status().equals("UNREVIEWED")) correction = previous.confirmedValue();
            Review review = new Review(rowNumber, command.status(), note, correction,
                    run == null ? null : run.id(), result == null ? null : result.actual(), Instant.now().toString());
            if (command.status().equals("UNREVIEWED")) entries.remove(rowNumber); else entries.put(rowNumber, review);
            store.saveReviews(datasetId, data, caseId, entries, review);
            return review;
        }
    }
    static List<Comparison> compare(Case test, InferenceResponse response, Map<Integer, Review> reviews) {
        Map<List<String>, List<String>> values = new HashMap<>();
        Map<List<String>, String> evidence = new HashMap<>();
        if (response != null && response.optionMappings() != null) for (OptionMapping value : response.optionMappings()) {
            var key = List.of(value.optionId(), value.targetPurchaseOptionName());
            values.computeIfAbsent(key, k -> new ArrayList<>()).add(value.value());
            evidence.put(key, Objects.toString(value.evidenceText(), ""));
        }
        if (values.isEmpty() && response != null && response.aiAssessment() != null && response.aiAssessment().mappings() != null)
            for (var value : response.aiAssessment().mappings()) {
                if (value == null || value.optionId() == null || value.targetPurchaseOptionName() == null) continue;
                var key = List.of(value.optionId(), value.targetPurchaseOptionName());
                values.computeIfAbsent(key, k -> new ArrayList<>()).add(value.value());
                evidence.put(key, Objects.toString(value.evidenceText(), ""));
            }
        List<Comparison> result = new ArrayList<>();
        for (Reference reference : test.references()) {
            var key = List.of(reference.optionId(), reference.target());
            List<String> found = values.getOrDefault(key, List.of());
            String actual = found.isEmpty() ? null : found.size() == 1 ? found.get(0) : String.join(" / ", found.stream().map(v -> Objects.toString(v, "(빈 값)")).toList());
            Review review = reviews.get(reference.row());
            String confirmed = review == null ? "" : review.confirmedValue();
            String expected = confirmed == null || confirmed.isBlank() ? reference.baseline() : confirmed;
            String status = found.size() > 1 ? "DUPLICATE" : ValueComparison.compare(expected, actual);
            if (found.size() <= 1 && confirmed != null && !confirmed.isBlank() && !ValueComparison.matches(status)) status = "REGRESSION";
            result.add(new Comparison(reference.row(), reference.optionId(), reference.target(), reference.baseline(), confirmed, actual, status, evidence.getOrDefault(key, "")));
        }
        return result;
    }
    private static Row summarize(Case test, Run run, Map<Integer, Review> reviews) {
        int matched = 0, differences = 0, confirmed = 0, regressions = 0;
        if (run != null) for (Comparison c : run.comparisons()) {
            if (ValueComparison.matches(c.status())) matched++; else differences++;
            if (c.status().equals("REGRESSION") && run.mode().equals("AI")) regressions++;
            Review review = reviews.get(c.row());
            if (review != null && review.status().equals("SUCCESS") && run.id().equals(review.runId()) && run.mode().equals("AI")) confirmed++;
        }
        String state = !test.issues().isEmpty() ? "INPUT_ISSUE" : run == null ? "UNTESTED" : run.error() != null ? "ERROR"
                : run.mode().equals("MOCK") ? "MOCK" : regressions > 0 ? "REGRESSION"
                : run.response() == null || !run.response().success() ? "REVIEW_REQUIRED"
                : confirmed == test.references().size() ? "CONFIRMED"
                : differences > 0 ? "MISMATCH" : "MATCH";
        return new Row(test.id(), test.request().goodsId(), test.request().goodsName(), test.request().categoryName(),
                test.request().options().size(), test.references().size(), state, run == null ? null : run.mode(), matched, differences,
                confirmed, regressions, test.issues().size() + (int) test.references().stream().filter(r -> !r.issues().isEmpty()).count(), run == null ? null : run.finishedAt());
    }
    private static InferenceException invalid(String message) { return new InferenceException("EVALUATION_INVALID_REQUEST", HttpStatus.BAD_REQUEST, message); }
}
