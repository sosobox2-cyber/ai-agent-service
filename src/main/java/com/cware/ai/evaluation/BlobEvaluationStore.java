package com.cware.ai.evaluation;

import com.fasterxml.jackson.databind.*;
import com.cware.ai.exception.InferenceException;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.*;
import static com.cware.ai.evaluation.EvaluationModels.*;

/** 원본과 결과는 불변 객체로 저장하고, 목록·실행 참조·검토 이력은 ETag로 동시 변경을 보호한다. */
@Component
@ConditionalOnProperty(name="app.evaluation.enabled",havingValue="true")
@ConditionalOnExpression("'${app.evaluation.storage:file}' == 'blob'")
public class BlobEvaluationStore extends EvaluationStore {
    private static final String ROOT="evaluation/v1/";
    public record CaseState(String latestRun, List<String> runs, Map<Integer,Review> reviews, List<String> reviewEvents, Row summary) {}
    public static class State { public Map<String,CaseState> cases=new LinkedHashMap<>(); }
    public static class Catalog { public List<Dataset> datasets=new ArrayList<>(); }
    private final PrivateBlobClient blob;
    private final ObjectMapper mapper;
    private final Map<String,Data> loaded=new ConcurrentHashMap<>();
    @Autowired public BlobEvaluationStore(@Value("${BLOB_READ_WRITE_TOKEN:}") String token,
            @Value("${app.evaluation.blob-store-id:}") String storeId,ObjectMapper mapper) throws IOException {
        this(new PrivateBlobClient(token,storeId),mapper);
    }
    BlobEvaluationStore(PrivateBlobClient blob,ObjectMapper mapper) throws IOException {
        super(System.getProperty("java.io.tmpdir")+"/evaluation-cache",mapper); this.blob=blob; this.mapper=mapper;
    }
    @Override public synchronized Dataset save(Imported imported) throws IOException {
        String id=imported.dataset().id(); String root=prefix(id);
        var bytes=new ByteArrayOutputStream();
        try(var gzip=new GZIPOutputStream(bytes); var writer=new OutputStreamWriter(gzip,StandardCharsets.UTF_8)) {
            for(Case c:imported.cases()) { writer.write(mapper.writeValueAsString(c)); writer.write('\n'); }
        }
        create(root+"cases.jsonl.gz",bytes.toByteArray());
        create(root+"metadata.json",mapper.writeValueAsBytes(imported.dataset()));
        create(root+"state.json",mapper.writeValueAsBytes(new State()));
        update(ROOT+"catalog.json",Catalog.class,c -> {
            if(c.datasets.stream().noneMatch(d -> d.id().equals(id))) c.datasets.add(imported.dataset());
        });
        Data data=new Data(imported.dataset()); imported.cases().forEach(c -> data.cases.put(c.id(),c)); loaded.put(id,data);
        return imported.dataset();
    }
    @Override public synchronized List<Dataset> datasets() throws IOException {
        var value=blob.get(ROOT+"catalog.json");
        if(value==null) return List.of();
        return mapper.readValue(value.bytes(),Catalog.class).datasets.stream().sorted(Comparator.comparing(Dataset::importedAt).reversed()).toList();
    }
    @Override synchronized Data data(String id) throws IOException {
        String root=prefix(id); Data data=loaded.get(id);
        if(data==null) {
            data=new Data(mapper.readValue(required(root+"metadata.json"),Dataset.class));
            try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(required(root+"cases.jsonl.gz"))),StandardCharsets.UTF_8))) {
                for(String line;(line=reader.readLine())!=null;) { var c=mapper.readValue(line,Case.class); data.cases.put(c.id(),c); }
            }
            loaded.put(id,data);
        }
        State state=state(id); data.summaries.clear();
        for(var entry:state.cases.entrySet()) if(entry.getValue().summary()!=null) data.summaries.put(entry.getKey(),entry.getValue().summary());
        return data;
    }
    @Override void refreshCase(String id,Data data,String caseId) throws IOException {
        CaseState c=state(id).cases.get(caseId);
        if(c==null) { data.latest.remove(caseId); data.reviews.remove(caseId); return; }
        if(c.latestRun()!=null) data.latest.put(caseId,readRun(id,caseId,c.latestRun()));
        else data.latest.remove(caseId);
        data.reviews.put(caseId,c.reviews());
    }
    @Override synchronized Run saveRun(String id,Data data,Run run) throws IOException {
        Case test=test(data,run.caseId()); Run[] saved={run};
        update(prefix(id)+"state.json",State.class,state -> {
            CaseState before=entry(state,run.caseId());
            Run current=new Run(run.id(),run.caseId(),run.startedAt(),run.finishedAt(),run.mode(),run.error(),run.response(),
                    EvaluationService.compare(test,run.response(),before.reviews()),run.request());
            String object=prefix(id)+"runs/"+run.caseId()+"/"+run.id()+"-"+UUID.randomUUID()+".json";
            try { create(object,mapper.writeValueAsBytes(current)); }
            catch(IOException e) { throw new UncheckedIOException(e); }
            // 상태 파일의 조건부 저장이 성공한 경우에만 결과 참조를 공개한다.
            saved[0]=current;
            var runs=new ArrayList<>(before.runs());
            runs.add(object);
            state.cases.put(run.caseId(),new CaseState(object,runs,before.reviews(),before.reviewEvents(),EvaluationService.summarize(test,current,before.reviews())));
        });
        data.latest.put(run.caseId(),saved[0]);
        return saved[0];
    }
    @Override synchronized void saveReviews(String id,Data data,String caseId,Map<Integer,Review> ignored,Review event) throws IOException {
        String eventKey=prefix(id)+"review-history/"+caseId+"/"+UUID.randomUUID()+".json";
        create(eventKey,mapper.writeValueAsBytes(event));
        update(prefix(id)+"state.json",State.class,state -> {
            CaseState before=entry(state,caseId); Run run=null;
            try { if(before.latestRun()!=null) run=mapper.readValue(required(before.latestRun()),Run.class); }
            catch(IOException e) { throw new UncheckedIOException(e); }
            if(!Objects.equals(event.runId(),run==null?null:run.id()))
                throw new InferenceException("EVALUATION_STALE_REVIEW",HttpStatus.CONFLICT,"새 실행 결과가 있습니다. 상세를 새로 열고 검토하세요.");
            var reviews=new LinkedHashMap<>(before.reviews());
            if(event.status().equals("UNREVIEWED")) reviews.remove(event.row()); else reviews.put(event.row(),event);
            var events=new ArrayList<>(before.reviewEvents()); events.add(eventKey);
            state.cases.put(caseId,new CaseState(before.latestRun(),before.runs(),reviews,events,EvaluationService.summarize(test(data,caseId),run,reviews)));
        });
        refreshCase(id,data,caseId);
    }
    @Override List<Run> history(String id,String caseId) throws IOException {
        List<Run> result=new ArrayList<>();
        for(String key:entry(state(id),caseId).runs()) result.add(mapper.readValue(required(key),Run.class));
        result.sort(Comparator.comparing(Run::startedAt).reversed()); return result;
    }
    @Override public synchronized Dataset restore(EvaluationArchive.Snapshot snapshot) throws IOException {
        Dataset dataset = snapshot.dataset();
        String root = prefix(dataset.id());
        State restored = new State();
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes); var writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            for (var c : snapshot.cases()) { writer.write(mapper.writeValueAsString(c.testCase())); writer.write('\n'); }
        }
        create(root + "cases.jsonl.gz", bytes.toByteArray());
        for (var c : snapshot.cases()) {
            String cid = c.testCase().id();
            List<String> runs = new ArrayList<>(), events = new ArrayList<>();
            for (Run run : c.runs()) {
                String key = root + "runs/" + cid + "/" + run.id() + ".json";
                create(key, mapper.writeValueAsBytes(run)); runs.add(key);
            }
            for (int i = 0; i < c.events().size(); i++) {
                String key = root + "review-history/" + cid + "/" + new UUID(0, i) + ".json";
                create(key, mapper.writeValueAsBytes(c.events().get(i))); events.add(key);
            }
            restored.cases.put(cid, new CaseState(c.latest() == null ? null : root + "runs/" + cid + "/" + c.latest().id() + ".json",
                    runs, c.reviews(), events, EvaluationService.summarize(c.testCase(), c.latest(), c.reviews())));
        }
        create(root + "state.json", mapper.writeValueAsBytes(restored));
        create(root + "metadata.json", mapper.writeValueAsBytes(dataset));
        // 원본·이력·상태가 모두 저장된 뒤 카탈로그에 공개한다. 재시도는 같은 업로드 ID로 중복을 막는다.
        update(ROOT + "catalog.json", Catalog.class, catalog -> {
            if (catalog.datasets.stream().noneMatch(d -> d.id().equals(dataset.id()))) catalog.datasets.add(dataset);
        });
        return dataset;
    }
    @Override public synchronized void backup(String id,OutputStream output) throws IOException {
        data(id); String root=prefix(id); State state=state(id);
        try(var zip=new ZipOutputStream(output)) {
            zip(zip,"metadata.json",required(root+"metadata.json"));
            try(var gzip=new GZIPInputStream(new ByteArrayInputStream(required(root+"cases.jsonl.gz")))) { zip(zip,"cases.jsonl",gzip.readAllBytes()); }
            zip(zip,"state.json",mapper.writeValueAsBytes(state));
            for(var c:state.cases.values()) {
                for(String key:c.runs()) zip(zip,key.substring(root.length()),required(key));
                for(String key:c.reviewEvents()) zip(zip,key.substring(root.length()),required(key));
            }
        }
    }
    private static void zip(ZipOutputStream zip,String name,byte[] value) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(value); zip.closeEntry(); }
    private Run readRun(String id,String caseId,String key) throws IOException {
        if(!key.startsWith(prefix(id)+"runs/"+caseId+"/")) throw new IOException("저장된 실행 경로가 잘못되었습니다.");
        return mapper.readValue(required(key),Run.class);
    }
    private State state(String id) throws IOException { return mapper.readValue(required(prefix(id)+"state.json"),State.class); }
    private static CaseState entry(State state,String id) { return state.cases.getOrDefault(id,new CaseState(null,List.of(),Map.of(),List.of(),null)); }
    private byte[] required(String key) throws IOException {
        var value=blob.get(key); if(value==null) throw new InferenceException("EVALUATION_NOT_FOUND",HttpStatus.NOT_FOUND,"데이터 또는 사례를 찾을 수 없습니다."); return value.bytes();
    }
    private void create(String key,byte[] bytes) throws IOException {
        if(!blob.put(key,bytes,null) && !Arrays.equals(required(key),bytes)) throw new IOException("이미 저장된 객체입니다.");
    }
    PrivateBlobClient client() { return blob; }
    private <T> void update(String key,Class<T> type,Consumer<T> change) throws IOException {
        for(int attempt=0;attempt<12;attempt++) {
            var old=blob.get(key); T value;
            try { value=old==null?type.getDeclaredConstructor().newInstance():mapper.readValue(old.bytes(),type); }
            catch(ReflectiveOperationException e) { throw new IOException("저장 형식을 초기화할 수 없습니다."); }
            try { change.accept(value); } catch(UncheckedIOException e) { throw e.getCause(); }
            if(blob.put(key,mapper.writeValueAsBytes(value),old==null?null:old.etag())) return;
        }
        throw new InferenceException("EVALUATION_STORAGE_CONFLICT",HttpStatus.CONFLICT,"다른 저장 작업과 충돌했습니다. 목록을 새로고침해 확인하세요.");
    }
    private static String prefix(String id) {
        if(id==null || !id.matches("[a-f0-9-]{36}")) throw new InferenceException("EVALUATION_NOT_FOUND",HttpStatus.NOT_FOUND,"데이터를 찾을 수 없습니다.");
        return ROOT+id+"/";
    }
}
