package com.cware.ai;

import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.inference.MappingProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 저장 제안만 재생한다. 네트워크와 원장을 사용하지 않는다. */
@EnabledIfSystemProperty(named = "v43.replay.input", matches = ".+")
class V43StoredProposalReplayTest {
    @Test void replay() throws Exception {
        var mapper = new ObjectMapper();
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : mapper.readTree(Files.readString(Path.of(System.getProperty("v43.replay.input"))))) {
            var request = mapper.treeToValue(row.path("request"), InferenceRequest.class);
            var proposal = mapper.treeToValue(row.path("proposal"), MappingProposal.class);
            var result = AiValueContractTest.assess(request, proposal);
            rows.add(Map.of("caseId", row.path("caseId").asText(), "before", row.path("before").asBoolean(),
                    "after", result.success(), "errors", result.validationErrors()));
        }
        assertThat(rows).hasSize(149);
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(System.getProperty("v43.replay.output", "target/v43-replay.json")).toFile(), rows);
        if (Boolean.getBoolean("v43.expect.baseline"))
            for (String id : List.of("c01237", "c03164", "c01818", "c04944"))
                assertThat(rows.stream().filter(r -> id.equals(r.get("caseId"))).findFirst().orElseThrow().get("after")).as(id).isEqualTo(true);
        else {
            for (String id : List.of("c01237", "c03164", "c01818", "c04944", "c00871", "c09710", "c09577"))
                assertThat(rows.stream().filter(r -> id.equals(r.get("caseId"))).findFirst().orElseThrow().get("after")).as(id).isEqualTo(false);
            assertThat(rows.stream().filter(r -> "c02023".equals(r.get("caseId"))).findFirst().orElseThrow().get("after")).isEqualTo(true);
        }
    }
}
