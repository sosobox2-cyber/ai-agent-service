package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
import java.util.regex.Pattern;

/** 원문에 명시된 N개/매 세트의 판매 단위와 세트 내 수량을 구분한다. */
public final class NamedSetContext {
    private static final Pattern CONTENT = Pattern.compile("(?<![\\d.\\-])([1-9]\\d{0,5})\\s*(개|매|피스|패치)(?:입)?\\s*세트");
    private static final Pattern SET_COUNT = Pattern.compile("(?<![\\d.])([1-9]\\d{0,5})\\s*세트");
    private static final Pattern NAME_CONTENT = Pattern.compile("(?<![\\d.])([1-9]\\d{0,5})\\s*(?:개|매|피스|패치)");
    private static final Pattern BONUS = Pattern.compile("무료\\s*체험|체험분|사은품|증정|무료\\s*샘플");
    private record SetInfo(String number, String unit, String source, String evidence) {}

    private NamedSetContext() {}

    private static String mainText(String source) {
        if (source == null) return "";
        var bonus = BONUS.matcher(source);
        return bonus.find() ? source.substring(0, bonus.start()) : source;
    }

    private static SetInfo find(InferenceRequest request) {
        if (!QuantityContext.eligible(request)) return null;
        SetInfo found = null;
        for (String source : new String[]{"goodsName", "productNoticeText", "productCompositionText"}) {
            String text = mainText(switch (source) {
                case "goodsName" -> request.goodsName();
                case "productCompositionText" -> request.productCompositionText();
                default -> request.productNoticeText();
            });
            var sets = SET_COUNT.matcher(text);
            while (sets.find()) if (!sets.group(1).equals("1")) return null;
            var content = CONTENT.matcher(text);
            while (content.find()) {
                if (found != null && (!found.number().equals(content.group(1))
                        || !found.unit().equals(content.group(2)))) return null;
                if (found == null) found = new SetInfo(content.group(1), content.group(2), source, content.group());
            }
        }
        if (found == null) return null;
        var name = NAME_CONTENT.matcher(mainText(request.goodsName()));
        while (name.find()) if (!name.group(1).equals(found.number())) return null;
        return found;
    }

    public static boolean hasSet(InferenceRequest request) { return find(request) != null; }

    public static boolean valid(InferenceRequest request, MappingProposal.Entry entry) {
        SetInfo set = find(request);
        if (set == null || entry.evidenceText() == null || entry.evidenceText().length() > 500) return false;
        String source = switch (entry.evidenceSource() == null ? "" : entry.evidenceSource()) {
            case "goodsName" -> mainText(request.goodsName());
            case "productNoticeText" -> mainText(request.productNoticeText());
            case "productCompositionText" -> mainText(request.productCompositionText());
            default -> "";
        };
        if (!source.contains(entry.evidenceText())) return false;
        var evidence = CONTENT.matcher(entry.evidenceText());
        if (!evidence.find() || !evidence.group(1).equals(set.number()) || !evidence.group(2).equals(set.unit())) return false;
        return switch (entry.targetPurchaseOptionName()) {
            case "수량" -> entry.value().equals("1세트");
            case "개당 수량" -> entry.value().equals(set.number() + "매입");
            default -> false;
        };
    }

    public static MappingProposal.Entry mockEntry(InferenceRequest request, String target) {
        SetInfo set = find(request);
        if (set == null) return null;
        String value = switch (target) {
            case "수량" -> "1세트";
            case "개당 수량" -> set.number() + "매입";
            default -> null;
        };
        return value == null ? null : new MappingProposal.Entry(request.options().get(0).optionId(), target,
                value, 0.0, set.source(), set.evidence());
    }
}
