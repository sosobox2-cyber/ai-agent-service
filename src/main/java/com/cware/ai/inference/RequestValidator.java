package com.cware.ai.inference;
import com.cware.ai.dto.*;
import com.cware.ai.exception.InferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
/** 필드 길이 등 Bean Validation 이후에 옵션명·단위 설정·단품 간 관계를 검사한다. */
public class RequestValidator {
    public void validate(InferenceRequest r) {
        // 모든 검증 오류를 모아 요청자가 한 번에 확인할 수 있도록 한다.
        List<String> errors=new ArrayList<>();
        // 허용 구매옵션명은 한 개 이상이어야 한다.
        if (r.allowedPurchaseOptions().isEmpty())
            errors.add("allowedPurchaseOptions에는 허용 구매옵션명을 한 개 이상 입력해야 합니다.");
        // Set으로 중복을 제거한 개수와 원본 개수를 비교해 중복 이름을 검사한다.
        if (new HashSet<>(r.allowedPurchaseOptions()).size()!=r.allowedPurchaseOptions().size())
            errors.add("allowedPurchaseOptions에 중복 이름이 있습니다.");
        // 이미 확인한 단품 ID와 단위 설정의 구매옵션명을 저장한다.
        Set<String> ids=new HashSet<>();
        Set<String> unitNames = new HashSet<>();
        for (PurchaseOptionUnit units : r.purchaseOptionUnits()) {
            // 단위 설정은 허용된 구매옵션에 대해서만 등록할 수 있다.
            if (!r.allowedPurchaseOptions().contains(units.purchaseOptionName()))
                errors.add("단위 설정의 구매옵션명은 allowedPurchaseOptions에 포함되어야 합니다.");
            // Set.add()는 이미 존재하는 값이면 false를 반환한다.
            if (!unitNames.add(units.purchaseOptionName()))
                errors.add("같은 구매옵션명의 단위 설정이 중복되었습니다.");
            // strip()으로 앞뒤 공백을 제거한 값과 비교해 기본단위의 공백을 검사한다.
            if (!units.defaultUnit().equals(units.defaultUnit().strip()))
                errors.add("기본단위에는 앞뒤 공백을 포함할 수 없습니다.");
            // 같은 구매옵션 내에서 단위 선택지가 중복되지 않도록 한다.
            if (new HashSet<>(units.unitOptions()).size() != units.unitOptions().size())
                errors.add("단위 선택지가 중복되었습니다.");
            // 각 단위 선택지에도 앞뒤 공백을 허용하지 않는다.
            for (String unit : units.unitOptions()) {
                if (!unit.equals(unit.strip()))
                    errors.add("단위에는 앞뒤 공백을 포함할 수 없습니다.");
            }
        }
        for (SourceOption o:r.options()) {
            // 단품 ID는 요청 내에서 고유해야 한다.
            if (!ids.add(o.optionId())) errors.add("optionId는 중복될 수 없습니다.");
            // 원본 옵션명은 null, 빈 문자열 또는 공백만 있는 문자열일 수 없다.
            if (o.optionName1() == null || o.optionName1().isBlank())
                errors.add("각 단품의 원본 옵션명이 필요합니다.");
        }
        // 오류가 있으면 동일한 메시지를 제거한 목록과 함께 HTTP 400 예외를 발생시킨다.
        if (!errors.isEmpty()) throw new InferenceException("INVALID_REQUEST",HttpStatus.BAD_REQUEST,
            "입력 데이터를 확인하세요.",errors.stream().distinct().toList());
    }
}
