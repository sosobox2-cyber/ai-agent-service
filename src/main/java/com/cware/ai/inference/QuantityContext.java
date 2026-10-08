package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
import java.util.regex.Pattern;

/** 단일상품의 수량, 개당 수량·용량에 한해 상품 원문 근거를 검사한다. */
public final class QuantityContext {
    private static final String PACKAGE = "(?:박스|팩|봉|통|세트|상자|병|튜브|개(?!입))";
    private static final String PIECE = "(?:패치|피스|개입|개|매)";
    private static final Pattern BONUS = Pattern.compile("무료\\s*체험|체험분|사은품|증정|무료\\s*샘플");
    private static final Pattern PACK_COUNT = Pattern.compile("(?<![\\d.])([1-9]\\d{0,5})\\s*" + PACKAGE);
    private static final Pattern PER_PACK = Pattern.compile("(?:1\\s*" + PACKAGE + "|개당|박스당|팩당|봉당|통당|세트당|상자당)");
    private static final Pattern PACK_CONTENT = Pattern.compile(
            "1\\s*" + PACKAGE + "\\s*[:：]\\s*[^)）\\n]{0,120}?총\\s*([1-9]\\d{0,5})\\s*" + PIECE);
    private static final String VOLUME_NUMBER = "(?:[1-9]\\d{0,5}(?:\\.\\d{1,3})?|0\\.\\d{1,3})";
    private static final Pattern VOLUME = Pattern.compile("(?<![\\d.])(" + VOLUME_NUMBER
            + ")\\s*(ml|l)(?![a-zA-Z])", Pattern.CASE_INSENSITIVE);

    private QuantityContext() {}

    public static boolean eligible(InferenceRequest request) {
        return request.options().size() == 1 && request.options().get(0).optionName1().strip().equals("단일상품");
    }

    public static boolean valid(InferenceRequest request, MappingProposal.Entry entry) {
        if (!eligible(request)) return false;
        if (NamedSetContext.hasSet(request) && (entry.targetPurchaseOptionName().equals("수량")
                || entry.targetPurchaseOptionName().equals("개당 수량")))
            return NamedSetContext.valid(request, entry);
        if (entry.targetPurchaseOptionName().equals("개당 중량") || entry.value().equals("1세트"))
            return BundleWeightContext.valid(request, entry);
        String unit = switch (entry.targetPurchaseOptionName()) {
            case "수량" -> "개";
            case "개당 수량" -> "개입";
            case "개당 용량" -> "volume";
            default -> null;
        };
        if (unit == null || entry.evidenceSource() == null || entry.evidenceText() == null
                || entry.evidenceText().isBlank() || entry.evidenceText().length() > 500) return false;
        String source = switch (entry.evidenceSource()) {
            case "goodsName" -> request.goodsName();
            case "productNoticeText" -> request.productNoticeText();
            case "productCompositionText" -> request.productCompositionText();
            default -> null;
        };
        if (source == null || !mainProductText(source).contains(entry.evidenceText())) return false;
        if (unit.equals("volume")) {
            var capacity = VOLUME.matcher(entry.value());
            if (!capacity.matches() || Double.parseDouble(capacity.group(1)) <= 0) return false;
            var evidence = VOLUME.matcher(entry.evidenceText());
            while (evidence.find()) {
                if (evidence.group(1).equals(capacity.group(1))
                        && evidence.group(2).equalsIgnoreCase(capacity.group(2))) return true;
            }
            return false;
        }
        var value = Pattern.compile("^([1-9]\\d{0,5})" + unit + "$").matcher(entry.value());
        if (!value.matches()) return false;
        String number = value.group(1);
        String countUnit = unit.equals("개") ? PACKAGE : PIECE;
        if (!Pattern.compile("(?<![\\d.])" + number + "\\s*" + countUnit).matcher(entry.evidenceText()).find())
            return false;
        return unit.equals("개") || PER_PACK.matcher(entry.evidenceText()).find();
    }

    /** 모의 추출은 명시된 개수·포장당 내용물 개수·ml/L 패턴만 다룬다. 계산이나 추정은 하지 않는다. */
    public static MappingProposal.Entry mockEntry(InferenceRequest request, String target) {
        if (!eligible(request)) return null;
        if (NamedSetContext.hasSet(request) && (target.equals("수량") || target.equals("개당 수량")))
            return NamedSetContext.mockEntry(request, target);
        var bundle = BundleWeightContext.mockEntry(request, target);
        if (bundle != null) return bundle;
        String[] sources = {"goodsName", "productNoticeText", "productCompositionText"};
        for (String sourceName : sources) {
            String source = switch (sourceName) {
                case "goodsName" -> request.goodsName();
                case "productCompositionText" -> request.productCompositionText();
                default -> request.productNoticeText();
            };
            if (source == null) continue;
            Pattern pattern = switch (target) {
                case "수량" -> PACK_COUNT;
                case "개당 수량" -> PACK_CONTENT;
                case "개당 용량" -> VOLUME;
                default -> null;
            };
            if (pattern == null) return null;
            var match = pattern.matcher(mainProductText(source));
            if (match.find()) {
                String unit = target.equals("수량") ? "개" : "개입";
                if (target.equals("개당 용량")) unit = match.group(2).equalsIgnoreCase("ml") ? "ml" : "L";
                var entry = new MappingProposal.Entry(request.options().get(0).optionId(), target,
                        match.group(1) + unit, 0.0, sourceName, match.group());
                if (valid(request, entry)) return entry;
            }
        }
        return null;
    }

    private static String mainProductText(String source) {
        var bonus = BONUS.matcher(source);
        return bonus.find() ? source.substring(0, bonus.start()) : source;
    }
}
