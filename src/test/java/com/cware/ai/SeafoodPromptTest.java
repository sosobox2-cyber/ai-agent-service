package com.cware.ai;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class SeafoodPromptTest {
    @Test void seafoodExampleUsesIndividualWeightAndRejectsConflictingTotal() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = Files.readString(Path.of("examples/seafood-request.json"));
        var request = mapper.readValue(json, com.cware.ai.dto.InferenceRequest.class);
        var proposal = com.cware.ai.inference.MockOptionInferenceService.infer(request);
        var validator = new com.cware.ai.inference.ResultValidator();
        assertThat(validator.validateProposal(request, proposal)).isEmpty();
        assertThat(validator.assemble(request, proposal).get(0).purchaseOptions())
                .containsEntry("수량", "5개");
        assertThat(validator.assemble(request, proposal).get(0).purchaseOptions())
                .containsEntry("수산물 중량", "160g");
        var conflicting = mapper.readValue(json.replace("800g", "900g"), com.cware.ai.dto.InferenceRequest.class);
        assertThat(com.cware.ai.inference.MockOptionInferenceService.infer(conflicting).mappings())
                .noneMatch(entry -> entry.targetPurchaseOptionName().equals("수산물 중량"));
    }
    @Test void fullModePrefersExplicitIndividualWeightOverTotal() throws Exception {
        for (String suffix : new String[]{""}) {
            String prompt = Files.readString(Path.of("src/main/resources/prompts/coupang-purchase-option-system" + suffix + ".txt"));
            assertThat(prompt).contains("수산물 중량=160g", "수량=5개", "확인에만 사용", "총중량만", "충돌");
        }
    }
}
