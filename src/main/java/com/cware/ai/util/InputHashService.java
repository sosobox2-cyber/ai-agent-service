package com.cware.ai.util;

import com.cware.ai.dto.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Service
/** 요청의 값은 보존하고 목록 순서는 정렬한 뒤 SHA-256으로 입력 식별 해시를 생성한다. */
public class InputHashService {
    private final ObjectMapper mapper;
    public InputHashService(ObjectMapper mapper) { this.mapper = mapper; }
    public String hash(InferenceRequest r) {
        // ID도 포함하여 다른 상품/단품에 캐시 결과가 잘못 재사용되는 것을 방지한다.
        Map<String,Object> canonical = new TreeMap<>();
        canonical.put("hashVersion", "input-v8");
        canonical.put("goodsId", r.goodsId());
        canonical.put("goodsName", r.goodsName());
        canonical.put("categoryName", r.categoryName());
        canonical.put("productNoticeText", r.productNoticeText());
        canonical.put("productCompositionText", r.productCompositionText());
        // 옵션명·단위·단품의 입력 순서만 달라져도 같은 내용이면 같은 해시를 생성한다.
        canonical.put("allowedPurchaseOptions", r.allowedPurchaseOptions().stream().sorted().toList());
        canonical.put("purchaseOptionUnits", r.purchaseOptionUnits().stream()
                .sorted(Comparator.comparing(PurchaseOptionUnit::purchaseOptionName))
                .map(u -> Map.of("purchaseOptionName", u.purchaseOptionName(), "defaultUnit", u.defaultUnit(),
                        "unitOptions", u.unitOptions().stream().sorted().toList())).toList());
        List<Map<String,Object>> options = new ArrayList<>();
        for (SourceOption o : r.options().stream().sorted(Comparator.comparing(SourceOption::optionId)).toList()) {
            Map<String,Object> row = new TreeMap<>();
            row.put("optionId", o.optionId());
            row.put("optionName1", o.optionName1());
            options.add(row);
        }
        canonical.put("options", options);
        try {
            // 정규화한 JSON의 UTF-8 바이트를 해싱하고 응답에 사용할 16진 문자열로 반환한다.
            byte[] json = mapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("입력 해시 생성에 실패했습니다.");
        }
    }
}
