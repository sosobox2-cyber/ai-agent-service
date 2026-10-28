package com.cware.ai.inference;

import com.cware.ai.dto.Calculation;
import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.QuantityEvidence;
import com.cware.ai.dto.QuantityEvidence.*;
import java.util.*;
import java.util.regex.Pattern;

/** 명시적 판매 개수가 없는 단일상품 한 건만 서버 정책으로 1개 처리한다. */
public final class ImplicitSingleQuantityPolicy {
    private ImplicitSingleQuantityPolicy() {}
    private static final Pattern EXPLICIT_COUNT = Pattern.compile(
            "(?i)(?<![0-9])[1-9][0-9]{0,5}\\s*(?:개(?:입)?|매(?:입)?|장|정(?:입)?|캡슐|포|봉|병|팩|롤|박스|세트|묶음|통|대|마리|미|튜브|입|ea|pcs|p)"
                    + "|[xX×*]\\s*[1-9][0-9]{0,5}|[1-9][0-9]*\\s*\\+\\s*[1-9][0-9]*"
                    + "|(?:한|두|세|네)\\s*(?:개|박스|팩|세트|병|봉|포|롤|통)");
    private static final Set<String> SINGLE = Set.of("단일상품", "단품");
    private static final List<String> SOURCES = List.of("goodsName", "productNoticeText", "productCompositionText", "optionName1");

    public static boolean eligible(InferenceRequest request) {
        if (request == null || request.options() == null || request.options().size() != 1
                || request.allowedPurchaseOptions() == null || !request.allowedPurchaseOptions().contains("수량")) return false;
        var option = request.options().get(0);
        if (option == null || option.optionName1() == null || !SINGLE.contains(option.optionName1().strip())) return false;
        var unit = request.purchaseOptionUnits().stream()
                .filter(u -> "수량".equals(u.purchaseOptionName())).findFirst().orElse(null);
        if (unit != null && !"개".equals(unit.defaultUnit()) && !unit.unitOptions().contains("개")) return false;
        for (String text : List.of(Objects.toString(request.goodsName(), ""),
                Objects.toString(request.productNoticeText(), ""), Objects.toString(request.productCompositionText(), ""))) {
            if (EXPLICIT_COUNT.matcher(text).find() || text.contains("세트")) return false;
        }
        return true;
    }

    public static MappingProposal normalize(InferenceRequest request, MappingProposal proposal) {
        if (!eligible(request) || proposal == null || proposal.mappings() == null) return proposal;
        String optionId = request.options().get(0).optionId();
        String quote = request.options().get(0).optionName1().strip();
        var existing = proposal.mappings().stream().filter(Objects::nonNull)
                .filter(e -> optionId.equals(e.optionId()) && "수량".equals(e.targetPurchaseOptionName())).toList();
        if (existing.size() > 1) return proposal; // 중복 매핑은 기존 구조 검증에서 반려한다.
        var entries = new ArrayList<MappingProposal.Entry>();
        for (var entry : proposal.mappings()) if (entry == null || !optionId.equals(entry.optionId())
                || !"수량".equals(entry.targetPurchaseOptionName())) entries.add(entry);
        var count = new MappingProposal.Entry(optionId, "수량", "1개", 1.0, "optionName1", quote,
                new Calculation(Calculation.Operation.PACK_COUNT, "개", List.of(),
                        new Calculation.Evidence("optionName1", quote)), MappingProposal.IMPLICIT_SINGLE_QUANTITY);
        entries.add(count);
        var context = proposal.quantityEvidence();
        if (context == null && request.allowedPurchaseOptions().stream()
                .anyMatch(t -> QuantityEvidenceValidator.TARGETS.contains(t) && !"수량".equals(t))) return proposal;
        if (context != null && context.bindings() != null) return proposal;
        if (context != null && context.sourceReviews() != null) {
            Set<String> seen = new HashSet<>();
            for (SourceReview review : context.sourceReviews())
                if (review == null || review.status() == null || review.status() == Status.AMBIGUOUS
                        || !optionId.equals(review.optionId())
                        || !SOURCES.contains(review.source()) || !seen.add(review.source())) return proposal;
        }
        List<Fact> facts = new ArrayList<>();
        if (context != null && context.facts() != null) for (Fact fact : context.facts())
            if (fact == null || fact.measure() != Measure.COUNT) facts.add(fact);
        String id = facts.stream().anyMatch(f -> f != null && "server-implicit-single".equals(f.id()))
                ? "server-implicit-single-count" : "server-implicit-single";
        facts.add(new Fact(id, optionId, "main", Role.MAIN, Measure.COUNT, Scope.TOTAL,
                null, "개", "optionName1", quote));
        List<SourceReview> reviews = new ArrayList<>();
        boolean onlyCountTarget = request.allowedPurchaseOptions().stream()
                .noneMatch(t -> QuantityEvidenceValidator.TARGETS.contains(t) && !"수량".equals(t));
        for (String source : SOURCES) {
            String input = switch (source) {
                case "goodsName" -> request.goodsName();
                case "productNoticeText" -> request.productNoticeText();
                case "productCompositionText" -> request.productCompositionText();
                default -> request.options().get(0).optionName1();
            };
            if (input == null || input.isBlank()) continue;
            SourceReview prior = context == null || context.sourceReviews() == null ? null : context.sourceReviews().stream()
                    .filter(r -> r != null && optionId.equals(r.optionId()) && source.equals(r.source()))
                    .findFirst().orElse(null);
            Status status = source.equals("optionName1") ? Status.EXTRACTED
                    : onlyCountTarget ? facts.stream().anyMatch(f -> f != null && source.equals(f.source()))
                            ? Status.EXTRACTED : Status.NO_RELEVANT_FACT
                    : prior == null ? Status.NO_RELEVANT_FACT : prior.status();
            reviews.add(new SourceReview(optionId, source, status));
        }
        return new MappingProposal(proposal.certain(), proposal.confidence(), entries, proposal.reason(),
                new QuantityEvidence(facts, null, reviews));
    }
}
