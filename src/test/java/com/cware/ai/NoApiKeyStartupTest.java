package com.cware.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.ai.openai.api-key=")
@AutoConfigureMockMvc
class NoApiKeyStartupTest {
    private static final String URL = "/api/v1/coupang/purchase-options/infer";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void realInferenceRequiresKey() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Fixtures.request())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("AI_NOT_CONFIGURED"));
    }

    @Test void aiRequestsReportMissingKey() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Fixtures.ambiguous())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("AI_NOT_CONFIGURED"));
    }

    @Test void testModeWorksWithoutKeyAndCannotBeApplied() throws Exception {
        mvc.perform(post(URL).param("testMode", "true").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Fixtures.ambiguous())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inferenceSource").value("TEST"))
                .andExpect(jsonPath("$.errorCode").value("TEST_MODE"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.autoApplyCandidate").value(false))
                .andExpect(jsonPath("$.items[0].purchaseOptions.색상").value("남색"))
                .andExpect(jsonPath("$.items[0].purchaseOptions.사이즈").value("100"));
    }
}
