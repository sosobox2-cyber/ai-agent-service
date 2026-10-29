package com.cware.ai;

import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.inference.MappingProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;

/**
 * 임의 계획의 저장 제안을 현재 서버 검증으로 재생해 결과만 기록한다. 사례별 단언은 하지 않는다.
 * 입력은 scripts/replay-proposals.cjs prepare 출력이고, 결과는 summarize로 확정값 기준 집계한다.
 */
@EnabledIfSystemProperty(named = "replay.input", matches = ".+")
class StoredProposalReplayRunnerTest {
    @Test void replayStoredProposals() throws Exception {
        var mapper = new ObjectMapper();
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : mapper.readTree(Files.readString(Path.of(System.getProperty("replay.input"))))) {
            var request = mapper.treeToValue(row.path("request"), InferenceRequest.class);
            var proposal = mapper.treeToValue(row.path("proposal"), MappingProposal.class);
            var result = AiValueContractTest.assess(request, proposal);
            rows.add(Map.of("caseId", row.path("caseId").asText(), "before", row.path("before").asBoolean(),
                    "after", result.success(), "errors", result.validationErrors()));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                Path.of(System.getProperty("replay.output", "target/replay-output.json")).toFile(), rows);
    }
}
