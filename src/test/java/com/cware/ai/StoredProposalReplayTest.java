package com.cware.ai;

import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.QuantityEvidence;
import com.cware.ai.inference.MappingProposal;
import com.cware.ai.inference.QuantityEvidenceValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 명시한 로컬 이력만 읽는다. AI·운영 API 호출 없이 최종 승인 변경을 비교한다. */
@EnabledIfSystemProperty(named = "evaluation.replay.dir", matches = ".+")
class StoredProposalReplayTest {
    @Test void replayV30FactsWithServerDerivedLinks() throws Exception {
        var mapper = new ObjectMapper();
        var directory = Path.of(System.getProperty("evaluation.replay.dir"));
        var rows = new ArrayList<Map<String, Object>>();
        for (String line : Files.readAllLines(directory.resolve("ledger.jsonl"))) {
            var event = mapper.readTree(line);
            if (!"completed".equals(event.path("event").asText())
                    || !event.path("trialId").asText().startsWith("v30-paired-")) continue;
            var run = mapper.readTree(Files.readString(directory.resolve(event.path("id").asText() + ".json")));
            var request = mapper.treeToValue(run.path("request"), InferenceRequest.class);
            var old = mapper.treeToValue(run.path("response").path("aiAssessment"), MappingProposal.class);
            var q = old.quantityEvidence();
            var proposal = new MappingProposal(old.certain(), old.confidence(), old.mappings(), old.reason(),
                    q == null ? null : new QuantityEvidence(q.facts(), null, q.sourceReviews()));
            var response = AiValueContractTest.assess(request, proposal);
            rows.add(Map.of("caseId", event.path("caseId").asText(), "before", run.path("response").path("success").asBoolean(),
                    "after", response.success(), "errors", response.validationErrors()));
        }
        assertThat(rows).hasSize(24);
        for (String bad : List.of("c00040", "c00611", "c00168"))
            assertThat(rows.stream().filter(row -> bad.equals(row.get("caseId"))).findFirst().orElseThrow().get("after")).isEqualTo(false);
        Files.createDirectories(Path.of("target"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/stored-proposal-replay-v31.json").toFile(), rows);
    }
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
            if (QuantityEvidenceValidator.required(request) && proposal.quantityEvidence() == null) {
                assertThat(result.success()).isFalse();
                assertThat(result.validationErrors()).anyMatch(e -> e.startsWith("QUANTITY_EVIDENCE_REQUIRED"));
            }
            rows.add(Map.of("caseId", event.path("caseId").asText(), "trialId", event.path("trialId").asText(id),
                    "before", run.path("response").path("success").asBoolean(), "after", result.success(),
                    "errors", result.validationErrors()));
        }
        assertThat(rows).isNotEmpty();
        // 운영 평가에서 확인한 서로 다른 속성·포장의 정상 대조군을 고정한다.
        // 과거 수량 제안은 새 근거 계약이 없어 반려한다. 비대상 정상 대조군만 그대로 승인한다.
        Set<String> controls = Set.of("c01513", "c01900", "c05519", "c07325");
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
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/stored-proposal-replay-v29.json").toFile(),
                Map.of("replayed", rows.size(), "missingResponse", missing,
                        "beforeApproved", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("before"))).count(),
                        "afterApproved", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("after"))).count(), "rows", rows));
    }

    @Test void annotatedControlFixturesPreserveAll55Values() throws Exception {
        var mapper = new ObjectMapper();
        var directory = Path.of(System.getProperty("evaluation.replay.dir"));
        Set<String> controls = Set.of("c01513", "c01900", "c00803", "c05158", "c00005", "c05519", "c07325");
        Set<String> checked = new HashSet<>();
        int values = 0;
        for (String line : Files.readAllLines(directory.resolve("ledger.jsonl"))) {
            var event = mapper.readTree(line);
            String caseId = event.path("caseId").asText();
            if (!"completed".equals(event.path("event").asText()) || !controls.contains(caseId)
                    || !event.path("trialId").asText().equals("v28-paired-" + caseId)) continue;
            var run = mapper.readTree(Files.readString(directory.resolve(event.path("id").asText() + ".json")));
            var request = mapper.treeToValue(run.path("request"), InferenceRequest.class);
            var proposal = mapper.treeToValue(run.path("response").path("aiAssessment"), MappingProposal.class);
            // 값은 바꾸지 않고 기존 명시 근거에 v29 계층을 붙인 대역이다. 새 AI 생성 품질을 뜻하지 않는다.
            var response = AiValueContractTest.assess(request, Fixtures.grounded(request, proposal));
            assertThat(response.validationErrors()).as(caseId).isEmpty();
            assertThat(response.success()).as(caseId).isTrue();
            assertThat(response.optionMappings().stream().map(m -> m.optionId() + "|" + m.targetPurchaseOptionName() + "|" + m.value()).toList())
                    .containsExactlyElementsOf(proposal.mappings().stream().map(m -> m.optionId() + "|" + m.targetPurchaseOptionName() + "|" + m.value()).toList());
            values += proposal.mappings().size(); checked.add(caseId);
        }
        assertThat(checked).isEqualTo(controls);
        assertThat(values).isEqualTo(55);
    }
}
