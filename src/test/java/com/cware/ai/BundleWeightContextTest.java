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

class BundleWeightContextTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private InferenceRequest request() throws Exception {
        return mapper.readValue(Files.readString(Path.of("examples/weight-request.json")), InferenceRequest.class);
    }

    private InferenceRequest named(String name) throws Exception {
        var r = request();
        return new InferenceRequest(r.goodsId(), name, r.brand(), r.categoryName(), r.coupangCategoryId(),
                r.coupangCategoryName(), r.allowedPurchaseOptions(), r.options(), r.productNoticeText());
    }

    private MappingProposal.Entry entry(InferenceRequest r, String target, String value) {
        return new MappingProposal.Entry("1", target, value, .99, "goodsName", r.goodsName());
    }

    @Test void kimchiBundlePassesAiValidationAndTestMode() throws Exception {
        var r = request();
        var proposal = new MappingProposal(true, .99, List.of(entry(r, "수량", "1세트"),
                entry(r, "개당 중량", "5.2kg")), "두 본품을 합친 1세트의 중량은 5.2kg입니다.");
        var service = new PurchaseOptionInferenceService(new RequestValidator(), ignored -> proposal,
                new ResultValidator(), new InputHashService(mapper), Fixtures.properties());
        var result = service.infer(r);
        assertThat(result.success()).isTrue();
        assertThat(result.items().get(0).purchaseOptions())
                .containsEntry("수량", "1세트").containsEntry("개당 중량", "5.2kg");
        var mock = service.infer(r, true);
        assertThat(mock.errorCode()).isEqualTo("TEST_MODE");
        assertThat(mock.validationErrors()).isEmpty();
        assertThat(mock.items().get(0).purchaseOptions())
                .containsEntry("수량", "1세트").containsEntry("개당 중량", "5.2kg");
    }

    @Test void addsMixedUnitsWithExactDecimalArithmetic() throws Exception {
        for (String name : List.of("김치 4.2kg + 파김치 1000g", "김치 4200g + 파김치 1KG")) {
            var r = named(name);
            assertThat(QuantityContext.valid(r, entry(r, "개당 중량", "5.2kg"))).isTrue();
            assertThat(QuantityContext.mockEntry(r, "개당 중량").value()).isEqualTo("5.2kg");
        }
        var r = named("김치 0.1kg + 파김치 0.2kg");
        assertThat(QuantityContext.mockEntry(r, "개당 중량").value()).isEqualTo("0.3kg");
    }

    @Test void rejectsWrongTotalIncompleteEvidenceNutritionAndMultipleOptions() throws Exception {
        var r = request();
        assertThat(QuantityContext.valid(r, entry(r, "개당 중량", "4.2kg"))).isFalse();
        assertThat(QuantityContext.valid(r, entry(r, "수량", "2세트"))).isFalse();
        assertThat(QuantityContext.valid(r, new MappingProposal.Entry("1", "개당 중량", "5.2kg", .99,
                "goodsName", "4.2kg + 파김치 1kg"))).isFalse();
        assertThat(QuantityContext.valid(r, new MappingProposal.Entry("1", "개당 중량", "5.2kg", .99,
                "productNoticeText", r.productNoticeText()))).isFalse();
        var multiple = Fixtures.withOptions(r, List.of(new SourceOption("1", "단일상품"), new SourceOption("2", "단일상품")));
        assertThat(QuantityContext.valid(multiple, entry(multiple, "개당 중량", "5.2kg"))).isFalse();
    }

    @Test void rejectsGiftsChoicesMultipliersAndMissingWeights() throws Exception {
        for (String name : List.of("김치 4.2kg + 증정 파김치 1kg", "김치 4.2kg + 무료 파김치 1kg",
                "김치 4.2kg + 파김치 1kg 택1", "김치 4.2kg + 파김치 1kg 2개",
                "김치 4.2kg + 파김치 1kg * 2", "김치 4.2kg + 파김치 1kg x2",
                "김치 4.2kg + 파김치 1,000g", "김치 4.2kg + 파김치", "김치 4.2kg",
                "김치 4.2kg + 파김치 0kg", "김치 4.2kg + 파김치 -1kg",
                "김치 4.2kg + 파김치 1kg + 증정 500g", "김치 4.2kg + 파김치 1kg 500g")) {
            var r = named(name);
            assertThat(QuantityContext.valid(r, entry(r, "수량", "1세트"))).as(name).isFalse();
            assertThat(QuantityContext.mockEntry(r, "개당 중량")).as(name).isNull();
        }
    }
}
