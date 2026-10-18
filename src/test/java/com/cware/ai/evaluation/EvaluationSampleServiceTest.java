package com.cware.ai.evaluation;

import com.cware.ai.exception.InferenceException;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EvaluationSampleServiceTest {
    @TempDir Path directory;
    @Test void repeatAndRestartReuseDatasetAndPreserveReview() throws Exception {
        Path file = directory.resolve("sample.xls");
        Files.write(file,EvaluationImporterTest.workbook(false));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var importer = new EvaluationImporter(factory.getValidator());
            var store = new EvaluationStore(directory.resolve("data").toString(),new ObjectMapper());
            var sample = new EvaluationSampleService(file.toString(),"구매옵션 정보2.xls",importer,store);
            var first = sample.load();
            new EvaluationService(store,mock(PurchaseOptionInferenceService.class)).review(first.id(),"c00001",2,
                    new EvaluationModels.ReviewRequest("REFERENCE_ISSUE","원문 확인","31개",null));
            assertThat(sample.load().id()).isEqualTo(first.id());
            var restarted = new EvaluationStore(directory.resolve("data").toString(),new ObjectMapper());
            assertThat(new EvaluationSampleService(file.toString(),"구매옵션 정보2.xls",importer,restarted).load().id()).isEqualTo(first.id());
            assertThat(restarted.data(first.id()).reviews.get("c00001").get(2).confirmedValue()).isEqualTo("31개");
            assertThat(restarted.datasets()).hasSize(1);
            // 같은 파일명이어도 내용이 바뀌면 기존 데이터·검토를 덮어쓰지 않는다.
            Files.write(file,EvaluationImporterTest.workbook(true));
            assertThat(sample.load().id()).isNotEqualTo(first.id());
            assertThat(store.datasets()).hasSize(2);
        }
    }
    @Test void missingSampleExplainsDirectUploadAlternative() throws Exception {
        var store = new EvaluationStore(directory.toString(),new ObjectMapper());
        var sample = new EvaluationSampleService("","",null,store);
        assertThatThrownBy(sample::load).isInstanceOf(InferenceException.class).hasMessageContaining("직접 가져올 수");
        assertThat(store.datasets()).isEmpty();
    }
}
