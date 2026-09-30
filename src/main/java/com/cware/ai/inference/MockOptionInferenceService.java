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
        List<MappingProposal.Entry> entries = new ArrayList<>();
        for (SourceOption option : request.options()) {
            for (String target : request.allowedPurchaseOptions()) {
                String value = extract(option.optionName1(), target);
                if (value != null)
                    entries.add(new MappingProposal.Entry(option.optionId(), target, value, 0.0));
            }
        }
        return new MappingProposal(true, 0.0, List.copyOf(entries),
                "테스트 모드의 제한적인 모의 추출 결과입니다. 실제 AI 판단이 아닙니다.");
    }

    private static String extract(String source, String target) {
        String name = target.toUpperCase(Locale.ROOT);
        if (name.equals("옵션") || name.equals("상품옵션")) return source;
        String[] tokens = source.strip().split("\\s+");
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
