package com.cware.ai.evaluation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.IOException;
import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.cware.ai.security.LocalEvaluationAccess;
import org.springframework.beans.factory.annotation.Value;
import static com.cware.ai.evaluation.EvaluationModels.*;

@RestController
@RequestMapping("/api/v1/evaluation")
@ConditionalOnProperty(name="app.evaluation.enabled", havingValue="true")
public class EvaluationController {
    private final EvaluationImporter importer;
    private final EvaluationStore store;
    private final EvaluationService service;
    private final EvaluationSampleService sample;
    private final LocalEvaluationAccess localAccess;
    private final boolean securityEnabled;
    public EvaluationController(EvaluationImporter importer, EvaluationStore store, EvaluationService service, EvaluationSampleService sample,
            LocalEvaluationAccess localAccess, @Value("${app.security.enabled:true}") boolean securityEnabled) {
        this.importer = importer; this.store = store; this.service = service; this.sample = sample;
        this.localAccess = localAccess; this.securityEnabled = securityEnabled;
    }
    @ModelAttribute public void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    public record Access(boolean localManagementAvailable, boolean managementRequiresApiKey, boolean aiRequiresApiKey) {}
    @GetMapping("/access") public Access access(HttpServletRequest request) {
        boolean local = localAccess.available(request);
        return new Access(local, securityEnabled && !local, securityEnabled && !local);
    }
    @GetMapping("/datasets") public List<Dataset> datasets() throws IOException { return store.datasets(); }
    @PostMapping("/sample") public Dataset sample() throws IOException { return sample.load(); }
    @PostMapping(value="/datasets", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Dataset upload(@RequestParam("file") MultipartFile file) throws IOException {
        return store.save(importer.read(file.getBytes(), file.getOriginalFilename()));
    }
    @GetMapping("/datasets/{id}/cases") public Page cases(@PathVariable String id,
            @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String state,
            @RequestParam(defaultValue="") String target, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="30") int size) throws IOException {
        return service.page(id, q, state, target, page, size);
    }
    @GetMapping("/datasets/{id}/cases/{caseId}") public Detail detail(@PathVariable String id, @PathVariable String caseId) throws IOException {
        return service.detail(id, caseId);
    }
    @PostMapping("/datasets/{id}/cases/{caseId}/runs") public Run run(@PathVariable String id, @PathVariable String caseId,
            @RequestBody RunRequest command) throws IOException { return service.run(id, caseId, command); }
    @PostMapping("/datasets/{id}/cases/{caseId}/mock-runs") public Run mock(@PathVariable String id, @PathVariable String caseId) throws IOException {
        // 모의 실행 버튼은 클라이언트 본문과 무관하게 모의 모드를 강제한다.
        return service.run(id, caseId, new RunRequest("MOCK", false));
    }
    @PutMapping("/datasets/{id}/cases/{caseId}/reviews/{row}") public Review review(@PathVariable String id,
            @PathVariable String caseId, @PathVariable int row, @RequestBody ReviewRequest command) throws IOException {
        return service.review(id, caseId, row, command);
    }
    @GetMapping("/datasets/{id}/backup") public ResponseEntity<StreamingResponseBody> backup(@PathVariable String id) throws IOException {
        store.data(id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"evaluation-" + id + ".zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(output -> store.backup(id, output));
    }
}
