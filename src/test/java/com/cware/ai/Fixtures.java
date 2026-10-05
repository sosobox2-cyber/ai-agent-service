package com.cware.ai;

import com.cware.ai.config.InferenceProperties;
import com.cware.ai.dto.*;
import com.cware.ai.inference.MappingProposal;
import java.time.Duration;
import java.util.List;

final class Fixtures {
    static InferenceRequest request() {
        return new InferenceRequest("100000123","남성 배기핏 바지","ABC","패션 > 남성의류",
                "123456","남성 바지",List.of("핏","색상","사이즈"),List.of("핏","색상","사이즈"),List.of(
                new SourceOption("1","배기핏 남색 100 "),
                new SourceOption("2","배기핏 남색 150"),
                new SourceOption("3","배기핏 남색 200")), null);
    }
    static InferenceRequest ambiguous() {
        var base = request();
        return new InferenceRequest(base.goodsId(),base.goodsName(),base.brand(),base.categoryName(),
                base.coupangCategoryId(),base.coupangCategoryName(),List.of("색상","사이즈"),List.of("색상","사이즈"),
                List.of(new SourceOption("1","남색 100")), base.productNoticeText());
    }
    static InferenceRequest withOptions(InferenceRequest r,List<SourceOption> options) {
        return new InferenceRequest(r.goodsId(),r.goodsName(),r.brand(),r.categoryName(),r.coupangCategoryId(),
                r.coupangCategoryName(),r.allowedPurchaseOptions(),r.requiredPurchaseOptions(),options,r.productNoticeText());
    }
    static InferenceRequest withNotice(InferenceRequest r, String text) {
        return new InferenceRequest(r.goodsId(),r.goodsName(),r.brand(),r.categoryName(),r.coupangCategoryId(),
                r.coupangCategoryName(),r.allowedPurchaseOptions(),r.requiredPurchaseOptions(),r.options(),text);
    }
    static MappingProposal proposal() {
        return new MappingProposal(true,.99,List.of(
                new MappingProposal.Entry("1","핏","배기핏",.99),
                new MappingProposal.Entry("1","색상","남색",.99),
                new MappingProposal.Entry("1","사이즈","100",.99),
                new MappingProposal.Entry("2","핏","배기핏",.99),
                new MappingProposal.Entry("2","색상","남색",.99),
                new MappingProposal.Entry("2","사이즈","150",.99),
                new MappingProposal.Entry("3","핏","배기핏",.99),
                new MappingProposal.Entry("3","색상","남색",.99),
                new MappingProposal.Entry("3","사이즈","200",.99)),"명확하게 매핑 가능");
    }
    static MappingProposal ambiguousProposal() {
        return new MappingProposal(true,.99,List.of(
                new MappingProposal.Entry("1","색상","남색",.99),
                new MappingProposal.Entry("1","사이즈","100",.99)),"명확하게 매핑 가능");
    }
    static InferenceProperties properties() {
        return new InferenceProperties(.95,"coupang-option-v3",Duration.ofSeconds(5),Duration.ofSeconds(30));
    }
}
