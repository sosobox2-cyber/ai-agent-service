package com.cware.ai.dto;
import java.util.Map;
/** 원본 단품 ID별로 구매옵션명과 값을 묶은 최종 조립 결과다. */
public record PurchaseOptionItem(String optionId, Map<String,String> purchaseOptions) {}
