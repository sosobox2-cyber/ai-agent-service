package com.cware.ai.dto;

import java.util.List;

/** AI 판정에 필요한 데이터만 전달한다. 상품 ID와 내부 카테고리는 서버의 로그·집계용이다. */
public record AiProductData(String goodsName, List<String> allowedPurchaseOptions, List<SourceOption> options,
        String productNoticeText, List<PurchaseOptionUnit> purchaseOptionUnits, String productCompositionText) {
    public static AiProductData from(InferenceRequest request) {
        return new AiProductData(request.goodsName(), request.allowedPurchaseOptions(), request.options(),
                request.productNoticeText(), request.purchaseOptionUnits(), request.productCompositionText());
    }
}
