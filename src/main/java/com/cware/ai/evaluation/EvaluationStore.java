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
public class EvaluationStore {
    private final Path root;
    private final ObjectMapper mapper;
    private final Map<String, Data> cache = new ConcurrentHashMap<>();
    static final class Data {
        final Dataset metadata;
        final Map<String, Case> cases = new LinkedHashMap<>();
        final Map<String, Run> latest = new ConcurrentHashMap<>();
        final Map<String, Map<Integer, Review>> reviews = new ConcurrentHashMap<>();
        final Map<String, Object> locks = new ConcurrentHashMap<>();
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
    public synchronized List<Dataset> datasets() throws IOException {
        List<Dataset> result = new ArrayList<>();
        try (var folders = Files.list(root)) {
            for (Path folder : folders.filter(Files::isDirectory).toList()) {
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
    synchronized void saveRun(String datasetId, Data data, Run run) throws IOException {
        Path folder = folder(datasetId);
        atomic(folder.resolve("runs").resolve(run.caseId()).resolve(run.id() + ".json"), run);
        atomic(folder.resolve("latest").resolve(run.caseId() + ".json"), run);
        data.latest.put(run.caseId(), run);
    }
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
