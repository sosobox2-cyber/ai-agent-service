package com.cware.ai.inference;
import com.cware.ai.dto.*;
import com.cware.ai.exception.InferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class RequestValidator {
    public void validate(InferenceRequest r) {
        List<String> errors=new ArrayList<>();
        if (r.allowedPurchaseOptions().isEmpty())
            errors.add("allowedPurchaseOptions에는 허용 구매옵션명을 한 개 이상 입력해야 합니다.");
        if (new HashSet<>(r.allowedPurchaseOptions()).size()!=r.allowedPurchaseOptions().size())
            errors.add("allowedPurchaseOptions에 중복 이름이 있습니다.");
        Set<String> ids=new HashSet<>();
        for (SourceOption o:r.options()) {
            if (!ids.add(o.optionId())) errors.add("optionId는 중복될 수 없습니다.");
            if (o.optionName1() == null || o.optionName1().isBlank())
                errors.add("각 단품의 원본 옵션명이 필요합니다.");
        }
        if (!errors.isEmpty()) throw new InferenceException("INVALID_REQUEST",HttpStatus.BAD_REQUEST,
            "입력 데이터를 확인하세요.",errors.stream().distinct().toList());
    }
}
