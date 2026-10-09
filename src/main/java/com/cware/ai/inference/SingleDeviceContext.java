package com.cware.ai.inference;

import com.cware.ai.dto.*;
import java.util.List;
import java.util.regex.Pattern;

/** 테스트용 단일 본체의 명시된 모델, 인치 크기, 판매 설치 구성 추출. */
final class SingleDeviceContext {
    static MappingProposal.Entry mockEntry(InferenceRequest request, SourceOption option, String target) {
        if (request.options().size() != 1 || !List.of("단일상품", "단품").contains(option.optionName1().strip())
                || request.purchaseOptionUnits().stream().anyMatch(u -> target.equals(u.purchaseOptionName()))) return null;
        String text = request.goodsName(), source = "goodsName", value;
        switch (target) {
            case "모델명/품번" -> {
                if (request.productNoticeText() == null) return null;
                text = request.productNoticeText(); source = "productNoticeText";
                var match = Pattern.compile("품명 및 모델명\\s*:\\s*([A-Za-z0-9][A-Za-z0-9-]*)\\s*(?=,|$)").matcher(text);
                if (!match.find()) return null;
                value = match.group(1); text = match.group();
                if (match.find() || !request.goodsName().contains(value)
                        || !value.matches(".*[A-Za-z].*") || !value.matches(".*[0-9].*")) return null;
            }
            case "화면크기 (cm/(인치))", "화면크기(in)" -> {
                var paired = ScreenSizeContext.mockEntry(request, option, target);
                if (paired != null) return paired;
                var match = Pattern.compile("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*인치").matcher(text);
                if (!match.find()) return null;
                value = match.group(1) + "인치"; text = match.group();
                if (match.find()) return null;
            }
            case "스탠드/벽걸이 구분" -> {
                boolean stand = text.contains("스탠드"), wall = text.contains("벽걸이");
                if (stand == wall) return null;
                value = stand ? "스탠드" : "벽걸이";
            }
            case "설치지원방식" -> {
                if (request.productCompositionText() != null) text += "\n" + request.productCompositionText();
                if (Pattern.compile("불가|불포함|미포함|제외|별도|경우|선택").matcher(text).find()) return null;
                boolean visit = Pattern.compile("방문\\s*설치|기사\\s*설치|설치\\s*포함").matcher(text).find();
                boolean self = Pattern.compile("자가\\s*설치|셀프\\s*설치").matcher(text).find();
                if (visit == self) return null;
                value = visit ? "방문설치" : "자가설치";
                if (!Pattern.compile(visit ? "방문\\s*설치|기사\\s*설치|설치\\s*포함" : "자가\\s*설치|셀프\\s*설치")
                        .matcher(request.goodsName()).find()) {
                    source = "productCompositionText"; text = request.productCompositionText();
                } else text = request.goodsName();
            }
            default -> { return null; }
        }
        return new MappingProposal.Entry(option.optionId(), target, value, 0.0, source, text, null);
    }
}
