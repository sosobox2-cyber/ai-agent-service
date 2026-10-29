package com.cware.ai;

import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.inference.MappingProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 로컬 v43 저장 제안의 서버 검증만 재생한다. 새 프롬프트의 생성 효과는 측정하지 않는다. */
@EnabledIfSystemProperty(named = "v44.replay.input", matches = ".+")
class V44StoredProposalReplayTest {
    @Test void replaySavedV43Proposals() throws Exception {
        var mapper = new ObjectMapper();
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : mapper.readTree(Files.readString(Path.of(System.getProperty("v44.replay.input"))))) {
            var request = mapper.treeToValue(row.path("request"), InferenceRequest.class);
            var proposal = mapper.treeToValue(row.path("proposal"), MappingProposal.class);
            var result = AiValueContractTest.assess(request, proposal);
            rows.add(Map.of("caseId", row.path("caseId").asText(), "before", row.path("before").asBoolean(),
                    "after", result.success(), "errors", result.validationErrors()));
            if (Boolean.getBoolean("v44.replay.baseline"))
                assertThat(result.success()).as(row.path("caseId").asText()).isEqualTo(row.path("before").asBoolean());
        }
        assertThat(rows).hasSize(149);
        for (String id : List.of("c00518", "c02467", "c02913", "c01125", "c00995", "c01343", "c06227", "c05233", "c00325", "c00871", "c03728")) {
            var row = rows.stream().filter(r -> id.equals(r.get("caseId"))).findFirst().orElseThrow();
            assertThat(row.get("before")).as(id).isEqualTo(true);
            assertThat(row.get("after")).as(id).isEqualTo(Boolean.getBoolean("v44.replay.baseline"));
        }
        for (String id : List.of("c10140", "c01527"))
            assertThat(rows.stream().filter(r -> id.equals(r.get("caseId"))).findFirst().orElseThrow().get("after")).as(id).isEqualTo(true);
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(System.getProperty("v44.replay.output", "target/v44-replay.json")).toFile(), rows);
    }
}
