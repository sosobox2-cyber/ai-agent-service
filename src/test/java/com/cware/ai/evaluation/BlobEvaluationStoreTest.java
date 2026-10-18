package com.cware.ai.evaluation;

import com.cware.ai.dto.*;
import com.cware.ai.exception.InferenceException;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;
import static com.cware.ai.evaluation.EvaluationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlobEvaluationStoreTest {
    static class MemoryBlob extends PrivateBlobClient {
        final Map<String,Value> files=new ConcurrentHashMap<>(); boolean conflict;
        MemoryBlob(){super("test-token","test-store");}
        @Override public synchronized Value get(String key){return files.get(key);}
        @Override public synchronized boolean put(String key,byte[] bytes,String expected) {
            if(conflict && key.endsWith("state.json")) {conflict=false;return false;}
            Value old=files.get(key);
            if(!Objects.equals(expected,old==null?null:old.etag())) return false;
            files.put(key,new Value(bytes.clone(),UUID.randomUUID().toString()));return true;
        }
    }
    private Imported sample() {
        var input=new InferenceRequest("product","계란","식품",List.of("수량"),List.of(new SourceOption("001","단일상품")),"수량: 30개");
        var test=new Case("c00001",input,List.of(new Reference(2,"001","수량","30개","","",List.of())),List.of());
        return new Imported(new Dataset(UUID.randomUUID().toString(),"sample.xls","hash",Instant.now().toString(),1,1,1,0),List.of(test));
    }
    private InferenceResponse result(String value) {
        return new InferenceResponse("product",true,true,.99,List.of(new OptionMapping("001","단일상품","수량",value,.99,"goodsName","원문",null)),List.of(),"fixture",List.of(),null,"hash","v-test","AI");
    }
    @Test void restartAndAnotherInstanceSeeSameDataRunsReviewsAndBackup() throws Exception {
        var blob=new MemoryBlob();var mapper=new ObjectMapper();
        var a=new BlobEvaluationStore(blob,mapper); var dataset=a.save(sample());
        var ai=mock(PurchaseOptionInferenceService.class);when(ai.infer(any(),eq(false))).thenReturn(result("31개"),result("30개"));
        var serviceA=new EvaluationService(a,ai); var first=serviceA.run(dataset.id(),"c00001",new RunRequest("AI",true));
        var b=new BlobEvaluationStore(blob,mapper);var serviceB=new EvaluationService(b,ai);
        assertThat(b.datasets()).hasSize(1);
        assertThat(serviceB.detail(dataset.id(),"c00001").latest().request().goodsId()).isEqualTo("product");
        serviceB.review(dataset.id(),"c00001",2,new ReviewRequest("SUCCESS","확인","",first.id()));
        assertThat(serviceA.page(dataset.id(),"","CONFIRMED","",1,30).total()).isEqualTo(1);
        blob.conflict=true;
        Run second=serviceA.run(dataset.id(),"c00001",new RunRequest("AI",true));
        assertThat(second.comparisons().get(0).status()).isEqualTo("REGRESSION");
        assertThat(serviceB.detail(dataset.id(),"c00001").history()).hasSize(2);
        assertThatThrownBy(() -> serviceB.review(dataset.id(),"c00001",2,new ReviewRequest("SUCCESS","","",first.id())))
                .isInstanceOf(InferenceException.class).hasMessageContaining("새 실행 결과");
        var bytes=new ByteArrayOutputStream();b.backup(dataset.id(),bytes);
        var names=new ArrayList<String>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())names.add(entry.getName());
        }
        assertThat(names).contains("metadata.json","cases.jsonl","state.json");
        assertThat(names.stream().filter(n -> n.startsWith("runs/")).count()).isEqualTo(2);
        assertThat(names.stream().filter(n -> n.startsWith("review-history/")).count()).isEqualTo(1);
        verify(ai,times(2)).infer(any(),eq(false));
    }
    @Test void chunkedUploadCanMoveBetweenInstancesAndCompleteIdempotently() throws Exception {
        var blob=new MemoryBlob();var mapper=new ObjectMapper();
        var a=new BlobEvaluationStore(blob,mapper);var b=new BlobEvaluationStore(blob,mapper);
        var importer=new EvaluationImporter(jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        var first=new EvaluationUploadService(a,importer,mapper);var second=new EvaluationUploadService(b,importer,mapper);
        byte[] excel=EvaluationImporterTest.workbook(false);
        var ticket=first.start(new EvaluationUploadService.Start("fixture.xls",excel.length));
        second.part(ticket.id(),0,excel);
        Dataset dataset=first.complete(ticket.id());
        assertThat(second.complete(ticket.id()).id()).isEqualTo(dataset.id());
        assertThat(b.datasets()).hasSize(1);
        assertThat(b.data(dataset.id()).cases).hasSize(1);
        assertThatThrownBy(() -> second.part(ticket.id(),1,new byte[0])).isInstanceOf(InferenceException.class);
        assertThatThrownBy(() -> second.complete("../bad")).isInstanceOf(InferenceException.class);
        assertThatThrownBy(() -> first.start(new EvaluationUploadService.Start("big.xls",33L*1024*1024))).isInstanceOf(InferenceException.class);
    }
}
