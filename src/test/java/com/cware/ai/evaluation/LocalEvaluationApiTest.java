package com.cware.ai.evaluation;

import com.cware.ai.dto.InferenceResponse;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.evaluation.enabled=true","app.evaluation.local-ui-enabled=true","app.evaluation.local-bind-address=127.0.0.1",
        "app.security.enabled=true","app.security.api-key=local-evaluation-test-key","app.security.api-key-id=local-test",
        "app.security.rate-limit.enabled=false","app.ai.usage-jsonl-enabled=false"})
@AutoConfigureMockMvc
class LocalEvaluationApiTest {
    static final Path ROOT;
    static { try { ROOT=Files.createTempDirectory("local-evaluation-test-"); } catch(Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.evaluation.data-directory",() -> ROOT.resolve("data").toString());
        Path sample=ROOT.resolve("sample.xls");
        try { Files.write(sample,EvaluationImporterTest.workbook(false)); } catch(Exception e) { throw new RuntimeException(e); }
        registry.add("app.evaluation.sample-file",sample::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean PurchaseOptionInferenceService inference;
    @Test void mainEditedRequestIsValidatedAndStoredWithRun() throws Exception {
        String sample=mvc.perform(post("/api/v1/evaluation/sample")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String base="/api/v1/evaluation/datasets/"+mapper.readTree(sample).path("id").asText()+"/cases/c00001";
        var detail=mapper.readTree(mvc.perform(get(base)).andReturn().getResponse().getContentAsString());
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)detail.path("testCase").path("request").deepCopy();
        String original=input.path("goodsName").asText();
        var command=mapper.createObjectNode().put("mode","AI").put("allowPaidCall",true); command.set("request",input);
        input.put("goodsName","");
        mvc.perform(post(base+"/runs").contentType("application/json").content(mapper.writeValueAsBytes(command)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(inference);
        input.put("goodsName","Edited main input");
        when(inference.infer(any(),eq(false))).thenReturn(new InferenceResponse(input.path("goodsId").asText(),false,false,0,
                List.of(),List.of(),"fixture",List.of(),"REVIEW_REQUIRED","hash","version","AI"));
        mvc.perform(post(base+"/runs").contentType("application/json").content(mapper.writeValueAsBytes(command)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.request.goodsName").value("Edited main input"));
        mvc.perform(get(base)).andExpect(jsonPath("$.latest.request.goodsName").value("Edited main input"))
                .andExpect(jsonPath("$.testCase.request.goodsName").value(original));
        verify(inference,times(1)).infer(any(),eq(false));
    }
    @Test void localEvaluationUsesServerCredentialsWhileExternalAccessStaysProtected() throws Exception {
        mvc.perform(get("/api/v1/evaluation/access")).andExpect(status().isOk())
                .andExpect(jsonPath("$.managementRequiresApiKey").value(false)).andExpect(jsonPath("$.aiRequiresApiKey").value(false));
        String sample=mvc.perform(post("/api/v1/evaluation/sample")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String base="/api/v1/evaluation/datasets/"+mapper.readTree(sample).path("id").asText();
        mvc.perform(get(base+"/cases")).andExpect(status().isOk());
        mvc.perform(get(base+"/git-snapshot")).andExpect(status().isOk()).andExpect(jsonPath("$.formatVersion").value(1));
        mvc.perform(get(base+"/git-snapshot").with(request -> { request.setServerName("external.example");return request; }))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(base+"/cases").with(request -> { request.setServerName("external.example");return request; }))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/evaluation/sample").header("Origin","https://external.example"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(base+"/cases/c00001/runs").header("Origin","https://external.example").contentType("application/json").content("{\"mode\":\"AI\",\"allowPaidCall\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/coupang/purchase-options/infer").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(inference);
        when(inference.infer(any(),eq(true))).thenReturn(new InferenceResponse("20019170",false,false,0,List.of(),List.of(),
                "모의",List.of(),"TEST_MODE","hash","version","TEST"));
        String run=mvc.perform(post(base+"/cases/c00001/mock-runs").contentType("application/json")
                .content("{\"mode\":\"AI\",\"allowPaidCall\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("MOCK")).andReturn().getResponse().getContentAsString();
        verify(inference).infer(any(),eq(true)); verify(inference,never()).infer(any(),eq(false));
        String runId=mapper.readTree(run).path("id").asText();
        mvc.perform(put(base+"/cases/c00001/reviews/2").contentType("application/json")
                .content(mapper.writeValueAsString(new EvaluationModels.ReviewRequest("NEEDS_FIX","실제 AI 확인 필요","",runId))))
                .andExpect(status().isOk());
        mvc.perform(post(base+"/cases/c00001/runs").contentType("application/json")
                .content("{\"mode\":\"AI\",\"allowPaidCall\":false}"))
                .andExpect(status().isBadRequest());
        verify(inference,never()).infer(any(),eq(false));
        when(inference.infer(any(),eq(false))).thenReturn(new InferenceResponse("20019170",false,false,0,List.of(),List.of(),
                "AI fixture",List.of(),"TEST_MODE","hash","version","TEST"));
        mvc.perform(post(base+"/cases/c00001/runs").contentType("application/json")
                .content("{\"mode\":\"AI\",\"allowPaidCall\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("AI"));
        verify(inference).infer(any(),eq(false));
    }
}
