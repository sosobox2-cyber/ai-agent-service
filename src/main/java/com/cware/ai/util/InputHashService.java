package com.cware.ai.util;

import com.cware.ai.dto.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Service
public class InputHashService {
    private final ObjectMapper mapper;
    public InputHashService(ObjectMapper mapper) { this.mapper = mapper; }
    public String hash(InferenceRequest r) {
        // ID도 포함하여 다른 상품/단품에 캐시 결과가 잘못 재사용되는 것을 방지한다.
        Map<String,Object> canonical = new TreeMap<>();
        canonical.put("hashVersion", "input-v6");
        canonical.put("goodsId", r.goodsId());
        canonical.put("goodsName", r.goodsName());
        canonical.put("brand", r.brand());
        canonical.put("productNoticeText", r.productNoticeText());
        canonical.put("productCompositionText", r.productCompositionText());
        canonical.put("categoryName", r.categoryName());
        canonical.put("coupangCategoryId", r.coupangCategoryId());
        canonical.put("coupangCategoryName", r.coupangCategoryName());
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
            byte[] json = mapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("입력 해시 생성에 실패했습니다.");
        }
    }
}
