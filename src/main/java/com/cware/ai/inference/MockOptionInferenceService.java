package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.SourceOption;
import java.util.*;

/** 화면과 API 흐름을 시험하는 제한적인 모의 추출기. 실제 AI 판단으로 사용하지 않는다. */
public final class MockOptionInferenceService {
    private static final Set<String> COLORS = Set.of(
            "남색", "네이비", "검정", "검정색", "블랙", "흰색", "화이트", "아이보리", "베이지",
            "카키", "그레이", "회색", "빨강", "레드", "파랑", "블루", "초록", "그린",
            "핑크", "보라", "퍼플", "노랑", "옐로우", "브라운", "갈색", "주황", "오렌지");

    private MockOptionInferenceService() {}

    public static MappingProposal infer(InferenceRequest request) {
        // 원본 단품 × 허용 구매옵션마다 전용 패턴을 먼저 적용하고 일반 추출로 이어 간다.
        List<MappingProposal.Entry> entries = new ArrayList<>();
        for (SourceOption option : request.options()) {
            for (String target : request.allowedPurchaseOptions()) {
                var device = SingleDeviceContext.mockEntry(request, option, target);
                if (device != null) {
                    entries.add(device);
                    continue;
                }
                var commonDimension = CommonDimensionContext.mockEntry(request, option, target, COLORS);
                if (commonDimension != null) {
                    entries.add(commonDimension);
                    continue;
                }
                var sheetRoll = SheetRollContext.mockEntry(request, option, target);
                if (sheetRoll != null) {
                    entries.add(sheetRoll);
                    continue;
                }
                var rollPack = RollPackContext.mockEntry(request, option, target);
                if (rollPack != null) {
                    entries.add(rollPack);
                    continue;
                }
                var configured = request.purchaseOptionUnits().stream()
                        .filter(u -> u.purchaseOptionName().equals(target)).findFirst();
                var packagedWeight = PackagedWeightContext.mockEntry(request, option, target);
                if (packagedWeight != null) {
                    entries.add(packagedWeight);
                    continue;
                }
                // 단위 설정이 있는 옵션은 전용 단위 추출로 처리하고 일반 문자열 추출로 넘기지 않는다.
                if (configured.isPresent()) {
                    MappingProposal.Entry entry = UnitSelection.mockEntry(request, option, configured.get());
                    if (entry != null) entries.add(entry);
                    continue;
                }
                String value = extract(option.optionName1(), target);
                if (value != null)
                    entries.add(new MappingProposal.Entry(option.optionId(), target, value, 0.0));
                else {
                    MappingProposal.Entry quantity = QuantityContext.mockEntry(request, target);
                    if (quantity != null) entries.add(quantity);
                    else {
                        MappingProposal.Entry screen = ScreenSizeContext.mockEntry(request, option, target);
                        if (screen != null) entries.add(screen);
                    }
                }
            }
        }
        // 모의 결과의 confidence는 0으로 두고 화면 확인용 계산 설명을 붙인다.
        return new MappingProposal(true, 0.0, entries.stream().map(MockCalculation::attach).toList(),
                "테스트 모드의 제한적인 모의 추출 결과입니다. 실제 AI 판단이 아닙니다.");
    }

    private static String extract(String source, String target) {
        // 원본을 공백·슬래시로 나눠 알려진 색상, 숫자 사이즈, '핏' 접미사만 추출한다.
        String name = target.toUpperCase(Locale.ROOT);
        if (name.equals("옵션") || name.equals("상품옵션")) return source;
        String[] tokens = source.strip().split("[\\s/]+");
        if (name.contains("색상") || name.contains("컬러") || name.equals("COLOR")) {
            for (String token : tokens) if (COLORS.contains(token.toLowerCase(Locale.ROOT))) return token;
        }
        if (name.contains("사이즈") || name.equals("SIZE") || name.contains("호수")) {
            for (int i = tokens.length - 1; i >= 0; i--)
                if (tokens[i].matches("\\d+(?:\\.\\d+)?(?:cm|mm|호)?")) return tokens[i];
        }
        if (name.contains("핏") || name.contains("스타일")) {
            for (String token : tokens) if (token.endsWith("핏")) return token;
        }
        return null;
    }
}
