package com.cware.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.evaluation.enabled=true", "app.security.enabled=true", "app.security.api-key=evaluation-test-key",
        "app.security.api-key-id=eval-test", "app.security.rate-limit.enabled=true", "app.security.rate-limit.per-minute=3",
        "app.ai.usage-jsonl-enabled=false"})
@AutoConfigureMockMvc
class EvaluationApiTest {
    static final Path DATA;
    static { try { DATA = Files.createTempDirectory("evaluation-api-test-"); } catch (Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.evaluation.data-directory", () -> DATA.resolve("data").toString());
        Path sample = DATA.resolve("sample.xls");
        try { Files.write(sample,EvaluationImporterTest.workbook(false)); } catch (Exception e) { throw new RuntimeException(e); }
        registry.add("app.evaluation.sample-file", sample::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    private static final String ROOT = "/api/v1/evaluation/datasets";

    @Test void protectsDatasetAndExercisesUploadRunReviewHistoryAndBackup() throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/evaluation/sample")).andExpect(status().isUnauthorized());
        var file = new MockMultipartFile("file","sample.xls","application/vnd.ms-excel",EvaluationImporterTest.workbook(false));
        mvc.perform(multipart(ROOT).file(file)).andExpect(status().isUnauthorized());
        String uploaded = mvc.perform(multipart(ROOT).file(file).header("X-API-Key","evaluation-test-key"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.rows").value(2)).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(uploaded).path("id").asText(), base = ROOT + "/" + id;
        for (int i=0;i<4;i++) mvc.perform(post("/api/v1/evaluation/sample").header("X-API-Key","evaluation-test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        // 관리용 조회는 추론 제한(분당 3회)을 소비하지 않는다.
        for (int i=0;i<5;i++) mvc.perform(get(base+"/cases").header("X-API-Key","evaluation-test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(post(base+"/cases/c00001/runs").header("X-API-Key","evaluation-test-key")
                .contentType("application/json").content("{\"mode\":\"AI\",\"allowPaidCall\":false}"))
                .andExpect(status().isBadRequest());
        String run = mvc.perform(post(base+"/cases/c00001/runs").header("X-API-Key","evaluation-test-key")
                .contentType("application/json").content("{\"mode\":\"MOCK\",\"allowPaidCall\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("MOCK")).andReturn().getResponse().getContentAsString();
        String runId = mapper.readTree(run).path("id").asText();
        mvc.perform(put(base+"/cases/c00001/reviews/2").header("X-API-Key","evaluation-test-key")
                .contentType("application/json").content(mapper.writeValueAsString(new EvaluationModels.ReviewRequest("SUCCESS","","",runId))))
                .andExpect(status().isBadRequest());
        mvc.perform(put(base+"/cases/c00001/reviews/2").header("X-API-Key","evaluation-test-key")
                .contentType("application/json").content(mapper.writeValueAsString(new EvaluationModels.ReviewRequest("NEEDS_FIX","실제 AI로 재검토","",runId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_FIX"));
        mvc.perform(get(base+"/cases/c00001").header("X-API-Key","evaluation-test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.reviews.2.note").value("실제 AI로 재검토"));
        var backup = mvc.perform(get(base+"/backup").header("X-API-Key","evaluation-test-key")).andExpect(request().asyncStarted()).andReturn();
        var complete = mvc.perform(asyncDispatch(backup)).andExpect(status().isOk()).andReturn();
        assertThat(complete.getResponse().getContentAsByteArray()).startsWith((byte)'P',(byte)'K');
        mvc.perform(get(base+"/git-snapshot")).andExpect(status().isUnauthorized());
        mvc.perform(get(base+"/git-snapshot").header("X-API-Key","evaluation-test-key"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.cases[0].reviews[0].note").value("실제 AI로 재검토"));
        byte[] bytes = complete.getResponse().getContentAsByteArray();
        String uploadRoot = "/api/v1/evaluation/imports";
        mvc.perform(post(uploadRoot).contentType("application/json").content("{\"filename\":\"backup.zip\",\"size\":"+bytes.length+"}"))
                .andExpect(status().isUnauthorized());
        String ticket = mvc.perform(post(uploadRoot).header("X-API-Key","evaluation-test-key").contentType("application/json")
                .content("{\"filename\":\"backup.zip\",\"size\":"+bytes.length+"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String restoreId = mapper.readTree(ticket).path("id").asText();
        mvc.perform(put(uploadRoot+"/"+restoreId+"/parts/0").header("X-API-Key","evaluation-test-key")
                .contentType("application/octet-stream").content(bytes)).andExpect(status().isOk());
        for (int attempt=0;attempt<2;attempt++) mvc.perform(post(uploadRoot+"/"+restoreId+"/complete").header("X-API-Key","evaluation-test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(restoreId));
        mvc.perform(get(ROOT+"/"+restoreId+"/cases/c00001").header("X-API-Key","evaluation-test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latest.id").value(runId))
                .andExpect(jsonPath("$.reviews.2.note").value("실제 AI로 재검토"));
    }
}
