package com.cware.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cware.ai.exception.InferenceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.cware.ai.evaluation.EvaluationModels.*;

/** 플랫폼 요청 크기 제한을 넘지 않도록 1MB씩 올리고 서버에서 원본을 조립한다. */
@Service
@ConditionalOnProperty(name="app.evaluation.enabled",havingValue="true")
public class EvaluationUploadService {
    static final int CHUNK=1024*1024;
    public record Start(String filename,long size) {}
    public record Ticket(String id,String filename,long size,int parts,String createdAt) {}
    private final EvaluationStore store;
    private final EvaluationImporter importer;
    private final ObjectMapper mapper;
    private final Path temporary;
    public EvaluationUploadService(EvaluationStore store,EvaluationImporter importer,ObjectMapper mapper) throws IOException {
        this.store=store;this.importer=importer;this.mapper=mapper;
        temporary=Files.createTempDirectory("evaluation-uploads-");
    }
    public Ticket start(Start command) throws IOException {
        if(command==null || command.size()<=0 || command.size()>32L*CHUNK || command.filename()==null || command.filename().length()>255
                || !command.filename().toLowerCase(Locale.ROOT).matches(".*\\.xlsx?")) throw invalid("32MB 이하의 Excel 파일을 선택하세요.");
        Ticket ticket=new Ticket(UUID.randomUUID().toString(),command.filename(),command.size(),(int)((command.size()+CHUNK-1)/CHUNK),Instant.now().toString());
        write(key(ticket.id(),"ticket.json"),mapper.writeValueAsBytes(ticket));return ticket;
    }
    public void part(String id,int part,byte[] bytes) throws IOException {
        Ticket t=ticket(id);
        long expected=part==t.parts()-1?t.size()-(long)part*CHUNK:CHUNK;
        if(part<0 || part>=t.parts() || bytes.length!=expected) throw invalid("업로드 조각의 크기 또는 순서가 잘못되었습니다.");
        write(key(id,"part-"+part),bytes);
    }
    public synchronized Dataset complete(String id) throws IOException {
        Ticket t=ticket(id);
        for(Dataset existing:store.datasets()) if(existing.id().equals(id)) return existing;
        var buffer=new ByteArrayOutputStream((int)t.size());
        for(int i=0;i<t.parts();i++) {
            byte[] part=read(key(id,"part-"+i));
            long expected=i==t.parts()-1?t.size()-(long)i*CHUNK:CHUNK;
            if(part.length!=expected) throw invalid("파일 업로드가 완료되지 않았습니다. 다시 가져오세요.");
            buffer.write(part);
        }
        Imported value=importer.read(buffer.toByteArray(),t.filename());
        Dataset d=value.dataset();
        return store.save(new Imported(new Dataset(id,d.filename(),d.sourceHash(),t.createdAt(),d.rows(),d.products(),d.options(),d.issueRows()),value.cases()));
    }
    private Ticket ticket(String id) throws IOException {
        Ticket t=mapper.readValue(read(key(id,"ticket.json")),Ticket.class);
        if(Instant.parse(t.createdAt()).isBefore(Instant.now().minusSeconds(86400))) throw invalid("업로드 유효 시간이 지났습니다. 다시 가져오세요.");
        return t;
    }
    private byte[] read(String key) throws IOException {
        if(store instanceof BlobEvaluationStore cloud) {
            var value=cloud.client().get(key); if(value!=null)return value.bytes();
        } else if(Files.isRegularFile(temporary.resolve(key))) return Files.readAllBytes(temporary.resolve(key));
        throw invalid("업로드 파일 조각이 없습니다. 다시 가져오세요.");
    }
    private void write(String key,byte[] bytes) throws IOException {
        if(store instanceof BlobEvaluationStore cloud) {
            if(!cloud.client().put(key,bytes,null) && !Arrays.equals(read(key),bytes)) throw invalid("다른 업로드 내용과 충돌했습니다.");
        } else { Path p=temporary.resolve(key); Files.createDirectories(p.getParent());Files.write(p,bytes); }
    }
    private static String key(String id,String suffix) {
        if(id==null || !id.matches("[a-f0-9-]{36}")) throw invalid("업로드 ID가 잘못되었습니다.");
        return "evaluation/uploads/"+id+"/"+suffix;
    }
    private static InferenceException invalid(String reason) { return new InferenceException("EVALUATION_UPLOAD_INVALID",HttpStatus.BAD_REQUEST,reason); }
}
