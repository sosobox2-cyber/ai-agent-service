package com.cware.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cware.ai.exception.InferenceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;
import static com.cware.ai.evaluation.EvaluationModels.*;

@Component
@ConditionalOnProperty(name="app.evaluation.enabled", havingValue="true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${app.evaluation.storage:file}' == 'file'")
public class EvaluationStore {
    private final Path root;
    private final ObjectMapper mapper;
    private final Map<String, Data> cache = new ConcurrentHashMap<>();
    static final class Data {
        volatile Dataset metadata;
        final Map<String, Case> cases = new LinkedHashMap<>();
        final Map<String, Run> latest = new ConcurrentHashMap<>();
        final Map<String, Map<Integer, Review>> reviews = new ConcurrentHashMap<>();
        final Map<String, Object> locks = new ConcurrentHashMap<>();
        final Map<String, Row> summaries = new ConcurrentHashMap<>();
        Data(Dataset metadata) { this.metadata = metadata; }
        Object lock(String caseId) { return locks.computeIfAbsent(caseId, key -> new Object()); }
    }
    public EvaluationStore(@Value("${app.evaluation.data-directory:data/evaluation}") String directory, ObjectMapper mapper) throws IOException {
        this.root = Path.of(directory).toAbsolutePath().normalize(); this.mapper = mapper;
        Files.createDirectories(root);
    }
    public synchronized Dataset save(Imported imported) throws IOException {
        Path folder = folder(imported.dataset().id());
        Files.createDirectories(folder);
        try (var writer = Files.newBufferedWriter(folder.resolve("cases.jsonl"), StandardCharsets.UTF_8)) {
            for (Case test : imported.cases()) { writer.write(mapper.writeValueAsString(test)); writer.newLine(); }
        }
        // metadata를 마지막에 저장하므로 중간 실패한 가져오기는 목록에 노출되지 않는다.
        atomic(folder.resolve("metadata.json"), imported.dataset());
        Data data = new Data(imported.dataset());
        imported.cases().forEach(test -> data.cases.put(test.id(), test));
        cache.put(data.metadata.id(), data);
        return data.metadata;
    }
    /** 운영 표본처럼 사례가 계속 늘어나는 데이터셋에 사례 하나와 첫 실행을 추가한다. 없으면 빈 데이터셋을 만든다. */
    synchronized void appendCase(Dataset dataset, Case test, Run run) throws IOException {
        Path folder = folder(dataset.id());
        if (!Files.exists(folder.resolve("metadata.json"))) save(new Imported(dataset, List.of()));
        Data data = data(dataset.id());
        if (data.cases.containsKey(test.id())) return;
        Files.writeString(folder.resolve("cases.jsonl"), mapper.writeValueAsString(test) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        data.cases.put(test.id(), test);
        saveRun(dataset.id(), data, run);
        updateCounts(data);
    }
    /** keep가 false인 사례와 그 실행·검토 파일을 지운다. 보관 기간이 지난 운영 표본 정리에 쓴다. */
    synchronized int prune(String datasetId, java.util.function.Predicate<String> keep) throws IOException {
        Data data = data(datasetId);
        List<String> removed = data.cases.keySet().stream().filter(id -> !keep.test(id)).toList();
        if (removed.isEmpty()) return 0;
        Path folder = folder(datasetId);
        removed.forEach(id -> { data.cases.remove(id); data.latest.remove(id); data.reviews.remove(id); data.summaries.remove(id); });
        Path stage = folder.resolve("cases.jsonl.tmp");
        try (var writer = Files.newBufferedWriter(stage, StandardCharsets.UTF_8)) {
            for (Case test : data.cases.values()) { writer.write(mapper.writeValueAsString(test)); writer.newLine(); }
        }
        Files.move(stage, folder.resolve("cases.jsonl"), StandardCopyOption.REPLACE_EXISTING);
        for (String id : removed) for (Path path : List.of(folder.resolve("latest").resolve(id + ".json"),
                folder.resolve("reviews").resolve(id + ".json"), folder.resolve("runs").resolve(id), folder.resolve("review-history").resolve(id)))
            deleteTree(path);
        updateCounts(data);
        return removed.size();
    }
    private void updateCounts(Data data) throws IOException {
        Dataset old = data.metadata;
        Dataset updated = new Dataset(old.id(), old.filename(), old.sourceHash(), old.importedAt(),
                data.cases.values().stream().mapToInt(c -> c.references().size()).sum(), data.cases.size(),
                data.cases.values().stream().mapToInt(c -> c.request().options().size()).sum(),
                (int) data.cases.values().stream().flatMap(c -> c.references().stream()).filter(r -> !r.issues().isEmpty()).count());
        atomic(folder(old.id()).resolve("metadata.json"), updated);
        data.metadata = updated;
    }
    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }
    public synchronized List<Dataset> datasets() throws IOException {
        List<Dataset> result = new ArrayList<>();
        try (var folders = Files.list(root)) {
            for (Path folder : folders.filter(Files::isDirectory).filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}")).toList()) {
                Path meta = folder.resolve("metadata.json");
                if (Files.exists(meta)) result.add(mapper.readValue(meta.toFile(), Dataset.class));
            }
        }
        result.sort(Comparator.comparing(Dataset::importedAt).reversed());
        return result;
    }
    synchronized Data data(String id) throws IOException {
        if (cache.containsKey(id)) return cache.get(id);
        Path folder = folder(id);
        if (!Files.exists(folder.resolve("metadata.json"))) throw missing();
        Data data = new Data(mapper.readValue(folder.resolve("metadata.json").toFile(), Dataset.class));
        try (var lines = Files.lines(folder.resolve("cases.jsonl"), StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                Case test = mapper.readValue(line, Case.class); data.cases.put(test.id(), test);
            }
        }
        Path latest = folder.resolve("latest"), reviews = folder.resolve("reviews");
        for (String caseId : data.cases.keySet()) {
            Path run = latest.resolve(caseId + ".json");
            if (Files.exists(run)) data.latest.put(caseId, mapper.readValue(run.toFile(), Run.class));
            Path review = reviews.resolve(caseId + ".json");
            if (Files.exists(review)) {
                Review[] saved = mapper.readValue(review.toFile(), Review[].class);
                Map<Integer, Review> entries = new LinkedHashMap<>();
                for (Review entry : saved) entries.put(entry.row(), entry);
                data.reviews.put(caseId, Map.copyOf(entries));
            }
        }
        cache.put(id, data); return data;
    }
    Case test(Data data, String caseId) {
        Case test = data.cases.get(caseId); if (test == null) throw missing(); return test;
    }
    synchronized Run saveRun(String datasetId, Data data, Run run) throws IOException {
        Path folder = folder(datasetId);
        atomic(folder.resolve("runs").resolve(run.caseId()).resolve(run.id() + ".json"), run);
        atomic(folder.resolve("latest").resolve(run.caseId() + ".json"), run);
        data.latest.put(run.caseId(), run);
        return run;
    }
    void refreshCase(String datasetId, Data data, String caseId) throws IOException { }
    synchronized void saveReviews(String datasetId, Data data, String caseId, Map<Integer, Review> entries, Review event) throws IOException {
        atomic(folder(datasetId).resolve("review-history").resolve(caseId).resolve(UUID.randomUUID() + ".json"), event);
        atomic(folder(datasetId).resolve("reviews").resolve(caseId + ".json"), entries.values());
        data.reviews.put(caseId, Map.copyOf(entries));
    }
    List<Run> history(String datasetId, String caseId) throws IOException {
        List<Run> result = new ArrayList<>();
        Path folder = folder(datasetId).resolve("runs").resolve(caseId);
        if (Files.isDirectory(folder)) try (var files = Files.list(folder)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList())
                result.add(mapper.readValue(file.toFile(), Run.class));
        }
        result.sort(Comparator.comparing(Run::startedAt).reversed());
        return result;
    }
    public EvaluationArchive.GitSnapshot gitSnapshot(String id) throws IOException {
        Data data = data(id);
        List<EvaluationArchive.GitCase> cases = new ArrayList<>();
        for (Case test : data.cases.values()) synchronized (data.lock(test.id())) {
            refreshCase(id, data, test.id());
            var reviews = data.reviews.getOrDefault(test.id(), Map.of()).values().stream()
                    .sorted(Comparator.comparingInt(Review::row)).toList();
            if (reviews.isEmpty()) continue;
            Set<String> runIds = new HashSet<>();
            reviews.forEach(review -> { if (review.runId() != null) runIds.add(review.runId()); });
            Run latest = data.latest.get(test.id());
            if (latest != null) runIds.add(latest.id());
            var runs = history(id, test.id()).stream().filter(run -> runIds.contains(run.id()))
                    .sorted(Comparator.comparing(Run::startedAt).thenComparing(Run::id)).toList();
            cases.add(new EvaluationArchive.GitCase(test, reviews, runs));
        }
        cases.sort(Comparator.comparing(c -> c.testCase().id()));
        return new EvaluationArchive.GitSnapshot(1, data.metadata, cases);
    }
    /** 모든 내용을 준비한 후 디렉터리를 이동해 완성된 복원본만 공개한다. */
    public synchronized Dataset restore(EvaluationArchive.Snapshot snapshot) throws IOException {
        Dataset dataset = snapshot.dataset();
        Path destination = folder(dataset.id());
        if (Files.exists(destination)) throw new IOException("이미 존재하는 복원 ID입니다.");
        Path stage = Files.createTempDirectory(root, "restore-");
        try {
            try (var writer = Files.newBufferedWriter(stage.resolve("cases.jsonl"), StandardCharsets.UTF_8)) {
                for (var c : snapshot.cases()) { writer.write(mapper.writeValueAsString(c.testCase())); writer.newLine(); }
            }
            for (var c : snapshot.cases()) {
                String cid = c.testCase().id();
                for (Run run : c.runs()) atomic(stage.resolve("runs").resolve(cid).resolve(run.id() + ".json"), run);
                if (c.latest() != null) atomic(stage.resolve("latest").resolve(cid + ".json"), c.latest());
                if (!c.reviews().isEmpty()) atomic(stage.resolve("reviews").resolve(cid + ".json"), c.reviews().values());
                for (int i = 0; i < c.events().size(); i++) atomic(stage.resolve("review-history").resolve(cid).resolve(new UUID(0, i).toString() + ".json"), c.events().get(i));
            }
            atomic(stage.resolve("metadata.json"), dataset);
            try { Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(stage, destination); }
            return dataset;
        } finally {
            if (Files.exists(stage)) try (var paths = Files.walk(stage)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    public synchronized void backup(String id, OutputStream output) throws IOException {
        data(id);
        Path folder = folder(id);
        try (ZipOutputStream zip = new ZipOutputStream(output); var files = Files.walk(folder)) {
            for (Path file : files.filter(Files::isRegularFile).filter(p -> !p.toString().endsWith(".tmp")).toList()) {
                zip.putNextEntry(new ZipEntry(folder.relativize(file).toString().replace('\\','/')));
                Files.copy(file, zip); zip.closeEntry();
            }
            zip.finish();
        }
    }
    private Path folder(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw missing();
        return root.resolve(id);
    }
    private void atomic(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "write-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), value);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static InferenceException missing() { return new InferenceException("EVALUATION_NOT_FOUND", HttpStatus.NOT_FOUND, "데이터 또는 사례를 찾을 수 없습니다."); }
}
