package com.cware.ai.evaluation;

import com.cware.ai.dto.*;
import com.cware.ai.exception.InferenceException;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import static com.cware.ai.evaluation.EvaluationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private Imported sample() {
        var input = new InferenceRequest("20019170","계란 30개","식품",List.of("수량"),List.of(new SourceOption("001","단일상품")),"수량: 30개",List.of(),"계란 30개");
        var test = new Case("c00001",input,List.of(new Reference(2,"001","수량","30개","없음>없음","단일상품",List.of())),List.of());
        return new Imported(new Dataset(UUID.randomUUID().toString(),"sample.xls","source-hash",Instant.now().toString(),1,1,1,0),List.of(test));
    }
    private InferenceResponse response(String value, boolean accepted) {
        return new InferenceResponse("20019170",accepted,accepted,.99,
                List.of(new OptionMapping("001","단일상품","수량",value,.99,"goodsName","계란",null)),
                List.of(),"test",List.of(),accepted?null:"TEST_MODE","input-hash","v-test",accepted?"AI":"TEST");
    }
    @Test void editedMainInputIsExecutedOnceAndStoredWithoutReplacingOriginal() throws Exception {
        var store = new EvaluationStore(directory.toString(),mapper); var dataset = store.save(sample());
        var inference = mock(PurchaseOptionInferenceService.class);
        var service = new EvaluationService(store,inference);
        var edited = new InferenceRequest("20019170","수정한 계란","식품",List.of("수량"),
                List.of(new SourceOption("001","31개")),"수량: 31개",List.of(),"수정한 구성");
        when(inference.infer(edited,false)).thenReturn(response("31개",true));
        var run = service.run(dataset.id(),"c00001",new RunRequest("AI",true,edited));
        verify(inference,times(1)).infer(edited,false);
        assertThat(run.request()).isEqualTo(edited);
        var restarted = new EvaluationService(new EvaluationStore(directory.toString(),mapper),inference);
        var detail = restarted.detail(dataset.id(),"c00001");
        assertThat(detail.history()).hasSize(1);
        assertThat(detail.latest().request()).isEqualTo(edited);
        assertThat(detail.testCase().request().goodsName()).isEqualTo("계란 30개");
        assertThat(detail.latest().comparisons().get(0).baseline()).isEqualTo("30개");
        var other = new InferenceRequest("another","다른 상품","식품",List.of("수량"),edited.options(),"수량: 31개");
        assertThatThrownBy(() -> service.run(dataset.id(),"c00001",new RunRequest("AI",true,other)))
                .isInstanceOf(InferenceException.class).hasMessageContaining("상품 ID");
        verifyNoMoreInteractions(inference);
        assertThat(restarted.detail(dataset.id(),"c00001").history()).hasSize(1);
    }
    @Test void approvalsAreTiedToRealRunAndPersistForFutureRegressionChecks() throws Exception {
        var store = new EvaluationStore(directory.toString(),mapper);
        var dataset = store.save(sample());
        var inference = mock(PurchaseOptionInferenceService.class);
        when(inference.infer(any(),eq(false))).thenReturn(response("31개",true),response("30개",true));
        var service = new EvaluationService(store,inference);
        var first = service.run(dataset.id(),"c00001",new RunRequest("AI",true));
        assertThat(first.comparisons().get(0).status()).isEqualTo("DIFFERENT");
        assertThat(service.page(dataset.id(),"","","",1,30).counts().getOrDefault("CONFIRMED",0L)).isZero();
        var approval = service.review(dataset.id(),"c00001",2,new ReviewRequest("SUCCESS","내용 확인","",first.id()));
        assertThat(approval.confirmedValue()).isEqualTo("31개");
        var second = service.run(dataset.id(),"c00001",new RunRequest("AI",true));
        assertThat(second.comparisons().get(0).status()).isEqualTo("REGRESSION");
        assertThat(second.comparisons().get(0).baseline()).isEqualTo("30개");
        assertThat(service.page(dataset.id(),"","","",1,30).rows().get(0).confirmed()).isZero();
        assertThatThrownBy(() -> service.review(dataset.id(),"c00001",2,new ReviewRequest("SUCCESS","","",first.id())))
                .isInstanceOf(InferenceException.class).hasMessageContaining("새 실행 결과");
        var restarted = new EvaluationService(new EvaluationStore(directory.toString(),mapper),inference);
        assertThat(restarted.detail(dataset.id(),"c00001").history()).hasSize(2);
        assertThat(restarted.detail(dataset.id(),"c00001").reviews().get(2).confirmedValue()).isEqualTo("31개");
        assertThat(restarted.page(dataset.id(),"","REGRESSION","수량",1,30).total()).isEqualTo(1);
        try (var bytes = new ByteArrayOutputStream()) { store.backup(dataset.id(),bytes); assertThat(bytes.size()).isGreaterThan(500); }
    }
    @Test void mockAndRejectedResultsCannotBeConfirmedAndPaidModeNeedsExplicitConsent() throws Exception {
        var store = new EvaluationStore(directory.toString(),mapper); var dataset = store.save(sample());
        var inference = mock(PurchaseOptionInferenceService.class);
        when(inference.infer(any(),eq(true))).thenReturn(response("30개",false));
        when(inference.infer(any(),eq(false))).thenReturn(response("30개",false));
        var service = new EvaluationService(store,inference);
        assertThatThrownBy(() -> service.run(dataset.id(),"c00001",new RunRequest("AI",false))).isInstanceOf(InferenceException.class);
        verifyNoInteractions(inference);
        var run = service.run(dataset.id(),"c00001",new RunRequest("MOCK",false));
        assertThat(run.comparisons().get(0).status()).isEqualTo("EXACT");
        assertThatThrownBy(() -> service.review(dataset.id(),"c00001",2,new ReviewRequest("SUCCESS","","",run.id())))
                .isInstanceOf(InferenceException.class).hasMessageContaining("실제 AI");
        var rejected = service.run(dataset.id(),"c00001",new RunRequest("AI",true));
        assertThatThrownBy(() -> service.review(dataset.id(),"c00001",2,new ReviewRequest("SUCCESS","","",rejected.id())))
                .isInstanceOf(InferenceException.class);
        assertThat(service.page(dataset.id(),"","","",1,30).rows().get(0).state()).isEqualTo("REVIEW_REQUIRED");
    }
    @Test void referenceCorrectionNeedsNoteAndDoesNotOverwriteOriginalBaseline() throws Exception {
        var store = new EvaluationStore(directory.toString(),mapper); var dataset = store.save(sample());
        var service = new EvaluationService(store,mock(PurchaseOptionInferenceService.class));
        assertThatThrownBy(() -> service.review(dataset.id(),"c00001",2,new ReviewRequest("REFERENCE_ISSUE","","31개",null)))
                .isInstanceOf(InferenceException.class);
        service.review(dataset.id(),"c00001",2,new ReviewRequest("REFERENCE_ISSUE","원문 확인","31개",null));
        var detail = service.detail(dataset.id(),"c00001");
        assertThat(detail.testCase().references().get(0).baseline()).isEqualTo("30개");
        assertThat(detail.reviews().get(2).confirmedValue()).isEqualTo("31개");
        assertThatThrownBy(() -> store.data("../secret")).isInstanceOf(InferenceException.class);
    }
}
