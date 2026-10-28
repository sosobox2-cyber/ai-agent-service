package com.cware.ai;

import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.inference.MappingProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 명시한 로컬 이력만 읽는다. AI·운영 API 호출 없이 최종 승인 변경을 비교한다. */
@EnabledIfSystemProperty(named = "evaluation.replay.dir", matches = ".+")
class StoredProposalReplayTest {
    @Test void replaySavedProposalsThroughFinalApproval() throws Exception {
        var mapper = new ObjectMapper();
        var directory = Path.of(System.getProperty("evaluation.replay.dir"));
        var rows = new ArrayList<Map<String, Object>>();
        Set<String> ids = new HashSet<>();
        int missing = 0;
        for (String line : Files.readAllLines(directory.resolve("ledger.jsonl"))) {
            var event = mapper.readTree(line);
            if (!"completed".equals(event.path("event").asText())) continue;
            String id = event.path("id").asText();
            assertThat(ids.add(id)).isTrue();
            var run = mapper.readTree(Files.readString(directory.resolve(id + ".json")));
            if (run.path("response").path("aiAssessment").isMissingNode()
                    || run.path("response").path("aiAssessment").isNull()) { missing++; continue; }
            var request = mapper.treeToValue(run.path("request"), InferenceRequest.class);
            var proposal = mapper.treeToValue(run.path("response").path("aiAssessment"), MappingProposal.class);
            var result = AiValueContractTest.assess(request, proposal);
            rows.add(Map.of("caseId", event.path("caseId").asText(), "trialId", event.path("trialId").asText(id),
                    "before", run.path("response").path("success").asBoolean(), "after", result.success(),
                    "errors", result.validationErrors()));
        }
        assertThat(rows).isNotEmpty();
        // 운영 평가에서 확인한 서로 다른 속성·포장의 정상 대조군을 고정한다.
        Set<String> controls = Set.of("c01513", "c01900", "c00803", "c05158", "c00005", "c05519", "c07325");
        for (String control : controls) {
            var matching = rows.stream().filter(row -> control.equals(row.get("caseId"))).toList();
            assertThat(matching).as(control).isNotEmpty();
            assertThat(matching).allSatisfy(row -> assertThat(row.get("after")).isEqualTo(true));
        }
        for (String bad : List.of("c03774", "c04671", "c08630", "c01154")) {
            var matching = rows.stream().filter(row -> bad.equals(row.get("caseId"))).toList();
            assertThat(matching).as(bad).isNotEmpty();
            assertThat(matching).allSatisfy(row -> assertThat(row.get("after")).isEqualTo(false));
        }
        Files.createDirectories(Path.of("target"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/stored-proposal-replay.json").toFile(),
                Map.of("replayed", rows.size(), "missingResponse", missing,
                        "beforeApproved", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("before"))).count(),
                        "afterApproved", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("after"))).count(), "rows", rows));
    }
}
