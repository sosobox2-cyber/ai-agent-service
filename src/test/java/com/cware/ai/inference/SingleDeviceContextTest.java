package com.cware.ai.inference;

import com.cware.ai.dto.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SingleDeviceContextTest {
    @Test void inchIsMappedSeparatelyAndInstallationNeedsAffirmativeEvidence() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var base = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                Files.readString(Path.of("examples/smartboard-request.json")));
        base.putArray("allowedPurchaseOptions").add("화면크기 (cm/(인치))").add("화면크기(in)")
                .add("화면크기(cm)").add("설치지원방식");
        for (String suffix : new String[]{"", " 방문설치", " 기사 설치", " 설치 포함", " 방문설치 불가", " 셀프설치", " 방문설치 / 셀프설치"}) {
            var body = base.deepCopy();
            body.put("goodsName", base.path("goodsName").asText() + suffix);
            var request = mapper.treeToValue(body, InferenceRequest.class);
            var proposal = MockOptionInferenceService.infer(request);
            var validator = new ResultValidator();
            assertThat(validator.validateProposal(request, proposal)).isEmpty();
            var values = validator.assemble(request, proposal).get(0).purchaseOptions();
            assertThat(values).containsEntry("화면크기 (cm/(인치))", "75인치")
                    .containsEntry("화면크기(in)", "75인치").containsEntry("화면크기(cm)", "없음");
            String expected = suffix.equals(" 셀프설치") ? "자가설치"
                    : java.util.Set.of(" 방문설치", " 기사 설치", " 설치 포함").contains(suffix) ? "방문설치" : "없음";
            assertThat(values).containsEntry("설치지원방식", expected);
        }
    }
    @Test void smartboardHasOneMappingPerTargetAndUsesBodyModel() throws Exception {
        var request = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                Files.readString(Path.of("examples/smartboard-request.json")), InferenceRequest.class);
        var proposal = MockOptionInferenceService.infer(request);
        var validator = new ResultValidator();
        assertThat(validator.validateProposal(request, proposal)).isEmpty();
        assertThat(proposal.mappings()).hasSize(3).allSatisfy(e -> assertThat(e.calculation()).isNull());
        assertThat(validator.assemble(request, proposal).get(0).purchaseOptions()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("화면크기 (cm/(인치))", "75인치", "모델명/품번", "75TR3DQ", "스탠드/벽걸이 구분", "스탠드"));
        var invalid = new MappingProposal(true, .9, List.of(proposal.mappings().get(1), proposal.mappings().get(1)), "중복");
        assertThat(validator.validateProposal(request, invalid)).isNotEmpty();
        for (String suffix : new String[]{""}) {
            assertThat(Files.readString(Path.of("src/main/resources/prompts/coupang-purchase-option-system" + suffix + ".txt")))
                    .contains("조건부 안내", "75TR3DQ", "calculation=null", "최종 값 하나만");
        }
    }
}
