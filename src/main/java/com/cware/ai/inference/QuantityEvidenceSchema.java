package com.cware.ai.inference;

import com.cware.ai.dto.*;
import java.util.*;

/** FULL의 수량 계열 요청에만 추가하는 strict JSON Schema. LIGHT 계약에는 포함하지 않는다. */
final class QuantityEvidenceSchema {
    private QuantityEvidenceSchema() {}
    static Map<String, Object> schema(InferenceRequest request) {
        var id = Map.of("type", "string", "enum", request.options().stream().map(SourceOption::optionId).toList());
        var source = Map.of("type", "string", "enum", List.of("goodsName", "productNoticeText", "productCompositionText", "optionName1"));
        var fact = object("id", string(), "optionId", id, "componentId", string(), "role", enumeration(QuantityEvidence.Role.values()),
                "measure", described(enumeration(QuantityEvidence.Measure.values()), "COUNT=개수, WEIGHT=중량, VOLUME=용량만. 길이 m/cm, 사이즈, 화면크기는 facts에서 제외한다."),
                "scope", described(enumeration(QuantityEvidence.Scope.values()), "17매×6팩: 17매는 PACK, 6팩은 TOTAL. ITEM은 한 제품의 중량/용량이다. 포장 단위가 팩이라고 PACK을 선택하지 않는다."),
                "source", source, "quote", string(), "amount", Map.of("type", List.of("string", "null")), "unit", string());
        var binding = object("optionId", id, "targetPurchaseOptionName", Map.of("type", "string", "enum",
                        request.allowedPurchaseOptions().stream().filter(QuantityEvidenceValidator.TARGETS::contains).toList()),
                "componentId", string(), "scope", described(enumeration(QuantityEvidence.Scope.values()), "수량=TOTAL, 개당 수량=PACK, 개당 중량/용량=ITEM. 연결한 facts의 scope와 같아야 한다."), "evidenceIds", array(string()));
        var review = object("optionId", id, "source", source, "status", enumeration(QuantityEvidence.Status.values()));
        return object("facts", described(array(fact), "모든 출처의 수량 계열 사실. 같은 값도 출처별로 남기며 충돌한 사실도 삭제하지 않는다."),
                "sourceReviews", described(array(review), "facts를 작성한 뒤 각 출처를 검토한다. EXTRACTED이면 동일 optionId/source의 fact가 반드시 있어야 한다."),
                "bindings", described(array(binding), "실제로 반환하는 수량 계열 매핑마다 하나. 같은 optionId/targetPurchaseOptionName 중복 금지. 생략한 매핑과 길이는 연결하지 않는다."));
    }
    private static Map<String, Object> described(Map<String, Object> schema, String description) {
        var result = new LinkedHashMap<>(schema); result.put("description", description); return result;
    }
    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static Map<String, Object> array(Object item) { return Map.of("type", "array", "items", item); }
    private static Map<String, Object> enumeration(Enum<?>[] values) {
        return Map.of("type", "string", "enum", Arrays.stream(values).map(Enum::name).toList());
    }
    private static Map<String, Object> object(Object... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) properties.put((String) entries[i], entries[i + 1]);
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
}
