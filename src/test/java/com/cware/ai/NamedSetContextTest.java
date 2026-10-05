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

class NamedSetContextTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private InferenceRequest request() throws Exception {
        return mapper.readValue(Files.readString(Path.of("examples/set-request.json")), InferenceRequest.class);
    }
    private MappingProposal.Entry entry(String target, String value, String source, String evidence) {
        return new MappingProposal.Entry("1", target, value, .9, source, evidence);
    }

    @Test void setContentsAndSalesCountPassValidationAndMock() throws Exception {
        var request = request();
        var proposal = new MappingProposal(true, .9, List.of(
                entry("수량", "1세트", "productNoticeText", "10개 세트"),
                entry("개당 수량", "10매입", "productNoticeText", "10개 세트")), "칫솔 10개로 구성된 1세트입니다.");
        var service = new PurchaseOptionInferenceService(new RequestValidator(), ignored -> proposal,
                new ResultValidator(), new InputHashService(mapper), Fixtures.properties());
        assertThat(service.infer(request).items().get(0).purchaseOptions())
                .containsEntry("수량", "1세트").containsEntry("개당 수량", "10매입");
        var mock = service.infer(request, true);
        assertThat(mock.validationErrors()).isEmpty();
        assertThat(mock.items().get(0).purchaseOptions())
                .containsEntry("수량", "1세트").containsEntry("개당 수량", "10매입");
    }

    @Test void onlyReturnsQuantityWhenItIsTheOnlyAllowedName() throws Exception {
        var r = request();
        var single = new InferenceRequest(r.goodsId(), r.goodsName(), r.brand(), r.categoryName(), r.coupangCategoryId(),
                r.coupangCategoryName(), List.of("수량"), r.options(), r.productNoticeText());
        var proposal = MockOptionInferenceService.infer(single);
        assertThat(proposal.mappings()).hasSize(1);
        assertThat(proposal.mappings().get(0).value()).isEqualTo("1세트");
        assertThat(new ResultValidator().validateProposal(single, proposal)).isEmpty();
    }

    @Test void rejectsSourceMixupRewrittenEvidenceAndWrongContents() throws Exception {
        var r = request();
        for (var entry : List.of(
                entry("수량", "10개", "goodsName", "10개"),
                entry("수량", "1세트", "goodsName", "[해즈픽] 시그니처 케어 칫솔 10개 세트"),
                entry("수량", "1세트", "productNoticeText", "10개세트"),
                entry("개당 수량", "2개입", "productNoticeText", "2개씩"),
                entry("개당 수량", "20개입", "productNoticeText", "10개 세트"))) {
            assertThat(QuantityContext.valid(r, entry)).isFalse();
        }
    }

    @Test void rejectsConflictsMultipleSetsAndGiftEvidence() throws Exception {
        var r = request();
        for (String notice : List.of("칫솔 12개 세트", "칫솔 10개 세트 2세트", "사은품 칫솔 10개 세트")) {
            var changed = Fixtures.withNotice(r, notice);
            assertThat(QuantityContext.valid(changed, entry("수량", "1세트", "productNoticeText", notice))).isFalse();
        }
        var multiple = Fixtures.withOptions(r, List.of(new SourceOption("1", "단일상품"), new SourceOption("2", "단일상품")));
        assertThat(QuantityContext.valid(multiple, entry("수량", "1세트", "productNoticeText", "10개 세트"))).isFalse();
    }

    @Test void sheetSetsUseSheetUnit() throws Exception {
        var r = request();
        var sheets = new InferenceRequest(r.goodsId(), "시트 10매", r.brand(), r.categoryName(), r.coupangCategoryId(),
                r.coupangCategoryName(), r.allowedPurchaseOptions(), r.options(), "시트 10매 세트");
        assertThat(QuantityContext.mockEntry(sheets, "개당 수량").value()).isEqualTo("10매입");
    }
}
