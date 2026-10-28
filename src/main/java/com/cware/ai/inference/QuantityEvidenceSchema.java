package com.cware.ai.inference;

import com.cware.ai.dto.*;
import java.util.*;

/** FULL의 수량 계열 요청에만 추가하는 strict JSON Schema. LIGHT 계약에는 포함하지 않는다. */
final class QuantityEvidenceSchema {
    private QuantityEvidenceSchema() {}
    static Map<String, Object> schema(InferenceRequest request) {
        var id = Map.of("type", "string", "enum", request.options().stream().map(SourceOption::optionId).toList());
        var source = Map.of("type", "string", "enum", List.of("goodsName", "productNoticeText", "productCompositionText", "optionName1"));
        var common = new LinkedHashMap<String, Object>();
        common.put("id", string()); common.put("optionId", id); common.put("componentId", string());
        common.put("role", enumeration(QuantityEvidence.Role.values()));
        common.put("source", source); common.put("quote", string());
        common.put("amount", Map.of("type", List.of("string", "null")));
        List<Object> variants = new ArrayList<>();
        variants.add(fact(common, QuantityEvidence.Measure.COUNT, List.of("PACK", "TOTAL"),
                QuantityEvidenceValidator.COUNTS.stream().sorted().toList()));
        variants.add(fact(common, QuantityEvidence.Measure.WEIGHT, List.of("ITEM"), List.of("mg", "g", "kg", "㎎", "㎏")));
        variants.add(fact(common, QuantityEvidence.Measure.VOLUME, List.of("ITEM"), List.of("ml", "mL", "L", "l", "cc", "㎖", "ℓ")));
        var review = object("optionId", id, "source", source, "status", enumeration(QuantityEvidence.Status.values()));
        return object("facts", described(array(Map.of("anyOf", variants)), "모든 출처의 개수·중량·용량 사실. 길이와 화면크기는 제외한다. 충돌한 사실도 삭제하지 않는다."),
                "sourceReviews", described(array(review), "facts를 작성한 뒤 각 출처를 검토한다. EXTRACTED이면 동일 optionId/source의 fact가 반드시 있어야 한다."));
    }
    private static Map<String, Object> fact(Map<String, Object> common, QuantityEvidence.Measure measure,
                                            List<String> scopes, List<String> units) {
        var fields = new LinkedHashMap<>(common);
        fields.put("measure", Map.of("type", "string", "enum", List.of(measure.name())));
        fields.put("scope", Map.of("type", "string", "enum", scopes));
        fields.put("unit", Map.of("type", "string", "enum", units));
        return object(fields);
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
        return object(properties);
    }
    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
}
