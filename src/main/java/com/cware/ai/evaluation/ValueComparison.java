package com.cware.ai.evaluation;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** 비교를 위해서만 표기를 정규화한다. 저장된 원문·단위를 바꾸지 않는다. */
public final class ValueComparison {
    private ValueComparison() {}
    private static final Pattern AMOUNT = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(kg|g|mg|l|ml|m|cm|mm)$");
    private static final Map<String, String> DIMENSION = Map.of("kg","weight","g","weight","mg","weight",
            "l","volume","ml","volume","m","length","cm","length","mm","length");
    private static final Map<String, BigDecimal> SCALE = Map.of("kg",new BigDecimal("1000"),"g",BigDecimal.ONE,
            "mg",new BigDecimal("0.001"),"l",new BigDecimal("1000"),"ml",BigDecimal.ONE,
            "m",new BigDecimal("1000"),"cm",new BigDecimal("10"),"mm",BigDecimal.ONE);
    public static String compare(String expected, String actual) {
        if (expected == null || expected.isBlank()) return "NO_REFERENCE";
        if (actual == null || actual.isBlank()) return "MISSING";
        if (expected.equals(actual)) return "EXACT";
        String left = normalize(expected), right = normalize(actual);
        if (left.equals(right)) return "EQUIVALENT";
        var a = AMOUNT.matcher(left); var b = AMOUNT.matcher(right);
        if (a.matches() && b.matches() && DIMENSION.get(a.group(2)).equals(DIMENSION.get(b.group(2)))
                && new BigDecimal(a.group(1)).multiply(SCALE.get(a.group(2)))
                .compareTo(new BigDecimal(b.group(1)).multiply(SCALE.get(b.group(2)))) == 0) return "EQUIVALENT";
        return "DIFFERENT";
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Z}]+", "").replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))", "");
    }
    public static boolean matches(String status) { return Set.of("EXACT", "EQUIVALENT").contains(status); }
}
