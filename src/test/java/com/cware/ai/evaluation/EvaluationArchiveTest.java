package com.cware.ai.evaluation;

import com.cware.ai.dto.*;
import com.cware.ai.exception.InferenceException;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import static com.cware.ai.evaluation.EvaluationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationArchiveTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private Imported sample() {
        var input = new InferenceRequest("product", "계란", "식품", List.of("수량"), List.of(new SourceOption("001", "단일상품")), "수량: 30개");
        var test = new Case("c00001", input, List.of(new Reference(2, "001", "수량", "30개", "", "", List.of())), List.of());
        return new Imported(new Dataset(UUID.randomUUID().toString(), "sample.xls", "hash", Instant.now().toString(), 1, 1, 1, 0), List.of(test));
    }
    private byte[] backup(EvaluationStore store, String id) throws Exception {
        var bytes = new ByteArrayOutputStream(); store.backup(id, bytes); return bytes.toByteArray();
    }
    private byte[] mutate(byte[] original, String name, byte[] replacement) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(original)); var zip = new ZipOutputStream(out)) {
            for (var e = input.getNextEntry(); e != null; e = input.getNextEntry()) {
                zip.putNextEntry(new ZipEntry(e.getName()));
                zip.write(e.getName().equals(name) ? replacement : input.readAllBytes()); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    @Test void fileToBlobToFilePreservesHistoryReviewsLatestAndRegressionWithoutCallingAi() throws Exception {
        var file = new EvaluationStore(temporary.resolve("original").toString(), mapper);
        var dataset = file.save(sample());
        var ai = mock(PurchaseOptionInferenceService.class);
        when(ai.infer(any(), eq(false))).thenReturn(new InferenceResponse("product", true, true, .99,
                List.of(new OptionMapping("001", "단일상품", "수량", "30개", .99, "goodsName", "원문", null)),
                List.of(), "fixture", List.of(), null, "hash", "v-test", "AI"));
        var service = new EvaluationService(file, ai);
        Run first = service.run(dataset.id(), "c00001", new RunRequest("AI", true));
        service.review(dataset.id(), "c00001", 2, new ReviewRequest("SUCCESS", "검토", "", first.id()));
        Run second = service.run(dataset.id(), "c00001", new RunRequest("MOCK", false));
        byte[] bytes = backup(file, dataset.id());
        var memory = new BlobEvaluationStoreTest.MemoryBlob();
        var cloud = new BlobEvaluationStore(memory, mapper);
        String cloudId = UUID.randomUUID().toString();
        var snapshot = EvaluationArchive.read(bytes, mapper, cloudId, Instant.now().toString());
        cloud.restore(snapshot);
        cloud.restore(snapshot); // 같은 업로드를 재시도해도 동일한 객체와 데이터 세트를 사용한다.
        var restarted = new BlobEvaluationStore(memory, mapper);
        var detail = new EvaluationService(restarted, ai).detail(cloudId, "c00001");
        assertThat(detail.history()).hasSize(2);
        assertThat(detail.latest()).isEqualTo(second);
        assertThat(detail.reviews().get(2).runId()).isEqualTo(first.id());
        assertThat(restarted.gitSnapshot(cloudId).cases().get(0).runs()).hasSize(2);
        var restored = new EvaluationStore(temporary.resolve("restored").toString(), mapper);
        String restoredId = UUID.randomUUID().toString();
        restored.restore(EvaluationArchive.read(backup(restarted, cloudId), mapper, restoredId, Instant.now().toString()));
        var readAgain = new EvaluationStore(temporary.resolve("restored").toString(), mapper);
        var roundTrip = new EvaluationService(readAgain, ai).detail(restoredId, "c00001");
        assertThat(roundTrip).isEqualTo(detail);
        assertThat(file.datasets()).containsExactly(dataset);
        assertThat(cloud.datasets()).hasSize(1);
        assertThat(backup(readAgain, restoredId)).isNotEmpty();
        verify(ai, times(1)).infer(any(), eq(false)); verify(ai, times(1)).infer(any(), eq(true));
    }
    @Test void chunkedRestoreCanMoveBetweenBlobInstancesAndRetryWithoutDuplicatingDataset() throws Exception {
        var source = new EvaluationStore(temporary.resolve("source").toString(), mapper);
        Dataset original = source.save(sample());
        byte[] bytes = backup(source, original.id());
        var memory = new BlobEvaluationStoreTest.MemoryBlob();
        var cloud = new BlobEvaluationStore(memory, mapper);
        var first = new EvaluationUploadService(cloud, null, mapper);
        var second = new EvaluationUploadService(new BlobEvaluationStore(memory, mapper), null, mapper);
        var ticket = first.start(new EvaluationUploadService.Start("backup.zip", bytes.length));
        second.part(ticket.id(), 0, bytes);
        Dataset restored = first.complete(ticket.id());
        assertThat(restored.id()).isNotEqualTo(original.id());
        assertThat(second.complete(ticket.id())).isEqualTo(restored);
        assertThat(cloud.datasets()).containsExactly(restored);
    }
    @Test void gitExportIsStableAndContainsReviewedCasesOnlyIncludingCorrectionsWithoutRuns() throws Exception {
        var store = new EvaluationStore(temporary.toString(), mapper);
        var original = sample();
        var second = new Case("c00002", original.cases().get(0).request(), original.cases().get(0).references(), List.of());
        Dataset d = original.dataset();
        var dataset = store.save(new Imported(new Dataset(d.id(), d.filename(), d.sourceHash(), d.importedAt(), 2, 2, 2, 0), List.of(original.cases().get(0), second)));
        var ai = mock(PurchaseOptionInferenceService.class);
        var service = new EvaluationService(store, ai);
        assertThat(store.gitSnapshot(dataset.id()).cases()).isEmpty();
        service.review(dataset.id(), "c00001", 2, new ReviewRequest("REFERENCE_ISSUE", "원본 확인", "31개", null));
        var snapshot = store.gitSnapshot(dataset.id());
        assertThat(snapshot.cases()).hasSize(1);
        assertThat(snapshot.cases().get(0).reviews().get(0).confirmedValue()).isEqualTo("31개");
        assertThat(snapshot.cases().get(0).runs()).isEmpty();
        assertThat(mapper.writeValueAsString(snapshot)).isEqualTo(mapper.writeValueAsString(store.gitSnapshot(dataset.id())));
        service.review(dataset.id(), "c00001", 2, new ReviewRequest("UNREVIEWED", "", "", null));
        assertThat(store.gitSnapshot(dataset.id()).cases()).isEmpty();
        verifyNoInteractions(ai);
    }
    @Test void failedBlobRestoreIsNotListedAndCanBeRetried() throws Exception {
        var source = new EvaluationStore(temporary.toString(), mapper);
        Dataset d = source.save(sample());
        var snapshot = EvaluationArchive.read(backup(source, d.id()), mapper, UUID.randomUUID().toString(), Instant.now().toString());
        var memory = new BlobEvaluationStoreTest.MemoryBlob();
        var cloud = new BlobEvaluationStore(memory, mapper);
        memory.conflict = true;
        assertThatThrownBy(() -> cloud.restore(snapshot)).isInstanceOf(InferenceException.class);
        assertThat(cloud.datasets()).isEmpty();
        cloud.restore(snapshot);
        assertThat(cloud.datasets()).containsExactly(snapshot.dataset());
    }
    @Test void rejectsMalformedOrDanglingBackupsBeforePublishingAnything() throws Exception {
        var store = new EvaluationStore(temporary.toString(), mapper);
        Dataset d = store.save(sample());
        byte[] bytes = backup(store, d.id());
        assertInvalid("not a zip".getBytes());
        assertInvalid(mutate(bytes, "metadata.json", "{}".getBytes()));
        assertInvalid(mutate(bytes, "cases.jsonl", "{\"id\":\"../../escape\"}".getBytes()));
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("../escape.json")); zip.write("{}".getBytes()); zip.closeEntry();
        }
        assertInvalid(out.toByteArray());
        var data = store.data(d.id());
        var run = new Run(UUID.randomUUID().toString(), "c00001", Instant.now().toString(), Instant.now().toString(), "MOCK", null, null, List.of(), null);
        store.saveRun(d.id(), data, run);
        var fakeSuccess = new Review(2, "SUCCESS", "", "30개", run.id(), "30개", Instant.now().toString());
        store.saveReviews(d.id(), data, "c00001", Map.of(2, fakeSuccess), fakeSuccess);
        assertInvalid(backup(store, d.id()));
        assertInvalid(mutate(backup(store, d.id()), "latest/c00001.json", "{}".getBytes()));
        assertThat(store.datasets()).hasSize(1);
    }
    @Test void rejectsZipBombAndLeavesDestinationEmpty() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("cases.jsonl"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 129; i++) zip.write(zeros);
            zip.closeEntry();
        }
        assertInvalid(bytes.toByteArray());
        assertThat(new EvaluationStore(temporary.toString(), mapper).datasets()).isEmpty();
    }
    private void assertInvalid(byte[] bytes) {
        assertThatThrownBy(() -> EvaluationArchive.read(bytes, mapper, UUID.randomUUID().toString(), Instant.now().toString()))
                .isInstanceOf(InferenceException.class).hasMessageContaining("백업 ZIP");
    }
}
