package com.cware.ai.evaluation;

import com.cware.ai.exception.InferenceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import static com.cware.ai.evaluation.EvaluationModels.*;

/** 파일/Blob 백업을 같은 모델로 검증한 다음 새 데이터 세트로 복원한다. */
public final class EvaluationArchive {
    private EvaluationArchive() {}
    static final long MAX_EXPANDED = 128L * 1024 * 1024;
    private static final String CASE_ID = "(?:c[0-9]+|p[0-9]{8}-[a-f0-9]{8})";
    public record History(Case testCase, Run latest, List<Run> runs, Map<Integer, Review> reviews, List<Review> events) {}
    public record Snapshot(Dataset dataset, List<History> cases) {}
    public record GitCase(Case testCase, List<Review> reviews, List<Run> runs) {}
    public record GitSnapshot(int formatVersion, Dataset source, List<GitCase> cases) {}

    static Snapshot read(byte[] bytes, ObjectMapper mapper, String id, String importedAt) {
        try {
            Map<String, byte[]> files = new LinkedHashMap<>();
            long total = 0;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.matches("(?:metadata\\.json|cases\\.jsonl|state\\.json|(?:latest|reviews)/" + CASE_ID + "\\.json|(?:runs|review-history)/" + CASE_ID + "/[a-f0-9-]{36,73}\\.json)")
                            || files.containsKey(name) || files.size() >= 100_000) throw invalid();
                    var out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    for (int n; (n = zip.read(buffer)) != -1;) {
                        total += n;
                        if (total > MAX_EXPANDED) throw invalid();
                        out.write(buffer, 0, n);
                    }
                    files.put(name, out.toByteArray());
                }
            }
            Dataset original = mapper.readValue(required(files, "metadata.json"), Dataset.class);
            if (original.id() == null || !original.id().matches("[a-f0-9-]{36}") || original.filename() == null || original.sourceHash() == null) throw invalid();
            Instant.parse(original.importedAt());
            List<Case> cases = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            try (var reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(required(files, "cases.jsonl")), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    Case test = mapper.readValue(line, Case.class);
                    if (test.id() == null || !test.id().matches(CASE_ID) || !ids.add(test.id()) || test.request() == null
                            || test.references() == null || test.references().isEmpty() || test.issues() == null) throw invalid();
                    cases.add(test);
                }
            }
            if ((cases.isEmpty() && !"production-sample".equals(original.sourceHash())) || original.products() != cases.size()
                    || original.rows() != cases.stream().mapToInt(c -> c.references().size()).sum()
                    || original.options() != cases.stream().mapToInt(c -> c.request().options().size()).sum()) throw invalid();
            BlobEvaluationStore.State state = files.containsKey("state.json")
                    ? mapper.readValue(files.get("state.json"), BlobEvaluationStore.State.class) : null;
            if (state != null && !ids.containsAll(state.cases.keySet())) throw invalid();
            List<History> histories = new ArrayList<>();
            Set<String> consumed = new HashSet<>(List.of("metadata.json", "cases.jsonl", "state.json"));
            Map<String, Map<String, byte[]>> historyFiles = new HashMap<>();
            for (var file : files.entrySet()) if (file.getKey().startsWith("runs/") || file.getKey().startsWith("review-history/")) {
                String caseId = file.getKey().split("/", 3)[1];
                historyFiles.computeIfAbsent(caseId, key -> new LinkedHashMap<>()).put(file.getKey(), file.getValue());
            }
            for (Case test : cases) {
                String cid = test.id();
                Map<String, Run> runs = new LinkedHashMap<>();
                List<Review> events = new ArrayList<>();
                for (var file : historyFiles.getOrDefault(cid, Map.of()).entrySet()) {
                    if (file.getKey().startsWith("runs/" + cid + "/")) {
                        Run run = mapper.readValue(file.getValue(), Run.class);
                        if (!cid.equals(run.caseId()) || run.id() == null || !run.id().matches("[a-f0-9-]{36}")
                                || run.comparisons() == null || !Set.of("AI", "MOCK").contains(run.mode())
                                || runs.putIfAbsent(run.id(), run) != null) throw invalid();
                        Instant.parse(run.startedAt()); Instant.parse(run.finishedAt());
                        consumed.add(file.getKey());
                    } else if (file.getKey().startsWith("review-history/" + cid + "/")) {
                        events.add(mapper.readValue(file.getValue(), Review.class)); consumed.add(file.getKey());
                    }
                }
                Run latest = null;
                Map<Integer, Review> reviews = new TreeMap<>();
                if (state != null) {
                    var cs = state.cases.get(cid);
                    if (cs != null) {
                        if (cs.latestRun() != null) {
                            String prefix = "evaluation/v1/" + original.id() + "/";
                            if (!cs.latestRun().startsWith(prefix + "runs/" + cid + "/")) throw invalid();
                            latest = mapper.readValue(required(files, cs.latestRun().substring(prefix.length())), Run.class);
                        }
                        reviews.putAll(cs.reviews());
                        String prefix = "evaluation/v1/" + original.id() + "/";
                        for (String key : cs.runs()) if (!key.startsWith(prefix + "runs/" + cid + "/") || !files.containsKey(key.substring(prefix.length()))) throw invalid();
                        for (String key : cs.reviewEvents()) if (!key.startsWith(prefix + "review-history/" + cid + "/") || !files.containsKey(key.substring(prefix.length()))) throw invalid();
                    }
                } else {
                    String latestFile = "latest/" + cid + ".json", reviewFile = "reviews/" + cid + ".json";
                    if (files.containsKey(latestFile)) { latest = mapper.readValue(files.get(latestFile), Run.class); consumed.add(latestFile); }
                    if (files.containsKey(reviewFile)) {
                        for (Review review : mapper.readValue(files.get(reviewFile), Review[].class))
                            if (reviews.putIfAbsent(review.row(), review) != null) throw invalid();
                        consumed.add(reviewFile);
                    }
                }
                if ((latest == null && !runs.isEmpty()) || (latest != null && !latest.equals(runs.get(latest.id())))) throw invalid();
                Set<Integer> rows = new HashSet<>();
                for (Reference ref : test.references()) if (!rows.add(ref.row()) || ref.target() == null || ref.optionId() == null || ref.issues() == null) throw invalid();
                for (var review : reviews.entrySet()) {
                    if (review.getKey() != review.getValue().row()) throw invalid();
                    validateReview(review.getValue(), rows, runs);
                }
                for (Review event : events) validateReview(event, rows, runs);
                histories.add(new History(test, latest, List.copyOf(runs.values()), reviews, events));
            }
            if (!consumed.containsAll(files.keySet())) throw invalid();
            Dataset restored = new Dataset(id, original.filename(), original.sourceHash(), importedAt,
                    original.rows(), original.products(), original.options(), original.issueRows());
            return new Snapshot(restored, histories);
        } catch (IOException | RuntimeException e) {
            if (e instanceof InferenceException known) throw known;
            throw invalid();
        }
    }
    private static void validateReview(Review review, Set<Integer> rows, Map<String, Run> runs) {
        if (!rows.contains(review.row()) || !Set.of("SUCCESS", "UNREVIEWED", "NEEDS_FIX", "REFERENCE_ISSUE").contains(review.status())
                || (review.runId() != null && !runs.containsKey(review.runId()))) throw invalid();
        Instant.parse(review.updatedAt());
        if ("SUCCESS".equals(review.status())) {
            Run run = runs.get(review.runId());
            if (run == null || !"AI".equals(run.mode()) || run.error() != null || run.response() == null || !run.response().success()
                    || run.comparisons().stream().noneMatch(c -> c.row() == review.row() && c.actual() != null && !c.actual().isBlank()
                    && !"DUPLICATE".equals(c.status()) && c.actual().equals(review.confirmedValue()) && c.actual().equals(review.reviewedValue()))) throw invalid();
        }
    }
    private static byte[] required(Map<String, byte[]> files, String name) {
        byte[] bytes = files.get(name); if (bytes == null) throw invalid(); return bytes;
    }
    static InferenceException invalid() { return new InferenceException("EVALUATION_BACKUP_INVALID", HttpStatus.BAD_REQUEST, "백업 ZIP의 형식·참조 또는 압축 해제 크기가 잘못되었습니다. 평가 화면에서 내려받은 백업을 선택하세요."); }
}
