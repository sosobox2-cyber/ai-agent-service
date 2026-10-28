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
                "measure", enumeration(QuantityEvidence.Measure.values()), "scope", enumeration(QuantityEvidence.Scope.values()),
                "source", source, "quote", string(), "amount", Map.of("type", List.of("string", "null")), "unit", string());
        var binding = object("optionId", id, "targetPurchaseOptionName", Map.of("type", "string", "enum",
                        request.allowedPurchaseOptions().stream().filter(QuantityEvidenceValidator.TARGETS::contains).toList()),
                "componentId", string(), "scope", enumeration(QuantityEvidence.Scope.values()), "evidenceIds", array(string()));
        var review = object("optionId", id, "source", source, "status", enumeration(QuantityEvidence.Status.values()));
        return object("sourceReviews", array(review), "facts", array(fact), "bindings", array(binding));
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
