package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class QuantityContextTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResultValidator validator = new ResultValidator();

    private InferenceRequest request() throws Exception {
        return mapper.readValue(Files.readString(Path.of("examples", "quantity-request.json")), InferenceRequest.class);
    }

    private MappingProposal proposal() {
        return new MappingProposal(true, .99, List.of(
                new MappingProposal.Entry("1", "수량", "9개", .99, "goodsName", "9박스"),
                new MappingProposal.Entry("1", "개당 수량", "50개입", .99,
                        "productNoticeText", "1박스: 5매, 총 50패치")), "본품 9박스, 박스당 50패치이며 무료체험분은 제외했습니다.");
    }

    @Test void acceptsEvidenceAndReturnsExpectedCountsThroughService() throws Exception {
        var request = request();
        var service = new PurchaseOptionInferenceService(new RequestValidator(), ignored -> proposal(),
                validator, new InputHashService(mapper), Fixtures.properties());
        var result = service.infer(request);
        assertThat(result.success()).isTrue();
        assertThat(result.items().get(0).purchaseOptions()).containsEntry("수량", "9개").containsEntry("개당 수량", "50개입");
        assertThat(result.optionMappings().get(1).evidenceText()).isEqualTo("1박스: 5매, 총 50패치");
    }

    @Test void testModeDemonstratesPatternWithoutClaimingAiSuccess() throws Exception {
        var request = request();
        var proposal = MockOptionInferenceService.infer(request);
        assertThat(validator.validateProposal(request, proposal)).isEmpty();
        assertThat(validator.assemble(request, proposal).get(0).purchaseOptions())
                .containsEntry("수량", "9개").containsEntry("개당 수량", "50개입");
        assertThat(proposal.confidence()).isZero();
    }

    @Test void rejectsUnsupportedOrMissingEvidenceAndSwappedUnits() throws Exception {
        var request = request();
        for (var entry : List.of(
                new MappingProposal.Entry("1", "수량", "9개", .99),
                new MappingProposal.Entry("1", "수량", "10개", .99, "goodsName", "9박스"),
                new MappingProposal.Entry("1", "개당 수량", "450개입", .99, "productNoticeText", "1박스: 5매, 총 50패치"),
                new MappingProposal.Entry("1", "개당 수량", "50개입", .99, "productNoticeText", "50패치"),
                new MappingProposal.Entry("1", "수량", "50개", .99, "productNoticeText", "1박스: 5매, 총 50패치"),
                new MappingProposal.Entry("1", "개당 수량", "9개입", .99, "goodsName", "9박스"))) {
            assertThat(QuantityContext.valid(request, entry)).isFalse();
        }
    }

    @Test void rejectsGiftCountsAndNonQuantityFields() throws Exception {
        var request = request();
        assertThat(QuantityContext.valid(request, new MappingProposal.Entry("1", "개당 수량", "10개입", .99,
                "productNoticeText", "무료체험분 1매(10패치)"))).isFalse();
        assertThat(QuantityContext.valid(request, new MappingProposal.Entry("1", "색상", "한국", .99,
                "productNoticeText", "제조국:한국"))).isFalse();
    }

    @Test void doesNotApplyProductCommonCountsToMultipleOptions() throws Exception {
        var request = Fixtures.withOptions(request(), List.of(
                new SourceOption("1", "단일상품"), new SourceOption("2", "단일상품")));
        assertThat(validator.validateProposal(request, proposal())).isNotEmpty();
        assertThat(MockOptionInferenceService.infer(request).mappings()).isEmpty();
    }

    @Test void resultValidatorDoesNotRecheckEvidenceAgainstCurrentInput() throws Exception {
        var changed = Fixtures.withNotice(request(), "1박스: 5매, 총 55패치");
        assertThat(validator.validateProposal(changed, proposal())).isEmpty();
    }

    @Test void sunscreenCountsAndCapacityPassAiProposalAndMockPaths() throws Exception {
        var request = mapper.readValue(Files.readString(Path.of("examples", "capacity-request.json")), InferenceRequest.class);
        var proposal = new MappingProposal(true, .99, List.of(
                new MappingProposal.Entry("1", "수량", "7개", .99, "goodsName", "7개"),
                new MappingProposal.Entry("1", "개당 용량", "50ml", .99, "productNoticeText", "선크림 50ml")),
                "선크림 7개 구성이고 개당 용량은 50ml입니다.");
        var service = new PurchaseOptionInferenceService(new RequestValidator(), ignored -> proposal,
                validator, new InputHashService(mapper), Fixtures.properties());
        var aiResult = service.infer(request);
        assertThat(aiResult.success()).isTrue();
        assertThat(aiResult.items().get(0).purchaseOptions()).containsEntry("수량", "7개").containsEntry("개당 용량", "50ml");
        var mockResult = service.infer(request, true);
        assertThat(mockResult.errorCode()).isEqualTo("TEST_MODE");
        assertThat(mockResult.items().get(0).purchaseOptions()).containsEntry("수량", "7개").containsEntry("개당 용량", "50ml");
    }

    @Test void rejectsCalculatedCapacitySpfAndUnrelatedUnits() throws Exception {
        var request = mapper.readValue(Files.readString(Path.of("examples", "capacity-request.json")), InferenceRequest.class);
        for (var entry : List.of(
                new MappingProposal.Entry("1", "개당 용량", "350ml", .99, "goodsName", "50ml 7개"),
                new MappingProposal.Entry("1", "개당 용량", "0.05L", .99, "productNoticeText", "선크림 50ml"),
                new MappingProposal.Entry("1", "개당 용량", "50ml", .99, "productNoticeText", "SPF50+ PA++++"),
                new MappingProposal.Entry("1", "개당 용량", "50g", .99, "productNoticeText", "선크림 50ml"),
                new MappingProposal.Entry("1", "수량", "50개", .99, "goodsName", "50ml 7개"))) {
            assertThat(QuantityContext.valid(request, entry)).isFalse();
        }
    }
}
