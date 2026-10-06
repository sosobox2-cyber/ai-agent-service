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
        Set<String> unitNames = new HashSet<>();
        for (PurchaseOptionUnit units : r.purchaseOptionUnits()) {
            if (!r.allowedPurchaseOptions().contains(units.purchaseOptionName()))
                errors.add("단위 설정의 구매옵션명은 allowedPurchaseOptions에 포함되어야 합니다.");
            if (!unitNames.add(units.purchaseOptionName()))
                errors.add("같은 구매옵션명의 단위 설정이 중복되었습니다.");
            if (!units.defaultUnit().equals(units.defaultUnit().strip()))
                errors.add("기본단위에는 앞뒤 공백을 포함할 수 없습니다.");
            if (new HashSet<>(units.unitOptions()).size() != units.unitOptions().size())
                errors.add("단위 선택지가 중복되었습니다.");
            for (String unit : units.unitOptions()) {
                if (!unit.equals(unit.strip()))
                    errors.add("단위에는 앞뒤 공백을 포함할 수 없습니다.");
            }
        }
        for (SourceOption o:r.options()) {
            if (!ids.add(o.optionId())) errors.add("optionId는 중복될 수 없습니다.");
            if (o.optionName1() == null || o.optionName1().isBlank())
                errors.add("각 단품의 원본 옵션명이 필요합니다.");
        }
        if (!errors.isEmpty()) throw new InferenceException("INVALID_REQUEST",HttpStatus.BAD_REQUEST,
            "입력 데이터를 확인하세요.",errors.stream().distinct().toList());
    }
}
