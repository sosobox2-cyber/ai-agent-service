package com.cware.ai.inference;

import com.cware.ai.dto.*;
import com.cware.ai.dto.QuantityEvidence.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** 명시적인 낱개 용량 × 포장 내 개수 × 판매 포장 수만 포장당 용량으로 계산한다. */
public final class PackCapacityNormalizer {
    private PackCapacityNormalizer() {}
    private static final Pattern EXPRESSION = Pattern.compile(
            "(?i)(?<![0-9])([1-9][0-9]*(?:\\.[0-9]+)?)\\s*(ml|l|cc)\\s*[x×*]\\s*([1-9][0-9]*)\\s*(포|개|병|캡슐|정)\\s*[x×*]\\s*([1-9][0-9]*)\\s*(세트|박스|팩|묶음)");
    private static final Pattern VOLUME = Pattern.compile("(?i)(?<![0-9])([1-9][0-9]*(?:\\.[0-9]+)?)\\s*(ml|l|cc)");
    record Expression(String itemAmount, String itemUnit, String contentAmount, String contentUnit,
                      String packAmount, String packUnit, String quote, String itemQuote, String contentQuote) {
        String output() { return new BigDecimal(itemAmount).multiply(new BigDecimal(contentAmount))
                .stripTrailingZeros().toPlainString() + itemUnit; }
    }

    static Expression expression(InferenceRequest request) {
        if (request.options().size() != 1 || request.productCompositionText() == null) return null;
        var matcher = EXPRESSION.matcher(request.productCompositionText());
        if (!matcher.find()) return null;
        var result = new Expression(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4),
                matcher.group(5), matcher.group(6), matcher.group(), matcher.group(1) + matcher.group(2),
                matcher.group(3) + matcher.group(4));
        if (matcher.find()) return null; // 여러 포장식 중 임의 선택하지 않는다.
        for (String source : List.of(Objects.toString(request.goodsName(), ""),
                Objects.toString(request.productNoticeText(), ""))) {
            var physical = VOLUME.matcher(source);
            while (physical.find()) if (!physical.group(1).equals(result.itemAmount())
                    || !physical.group(2).equalsIgnoreCase(result.itemUnit())) return null;
        }
        return result;
    }

    public static MappingProposal normalize(InferenceRequest request, MappingProposal proposal) {
        if (proposal == null || proposal.mappings() == null || proposal.quantityEvidence() == null
                || proposal.quantityEvidence().facts() == null || proposal.quantityEvidence().sourceReviews() == null
                || proposal.quantityEvidence().bindings() != null) return proposal;
        Expression x = expression(request);
        if (x == null || !request.allowedPurchaseOptions().containsAll(List.of("수량", "개당 용량"))) return proposal;
        String optionId = request.options().get(0).optionId();
        var counts = proposal.mappings().stream().filter(e -> e != null && optionId.equals(e.optionId())
                && "수량".equals(e.targetPurchaseOptionName())).toList();
        var volumes = proposal.mappings().stream().filter(e -> e != null && optionId.equals(e.optionId())
                && "개당 용량".equals(e.targetPurchaseOptionName())).toList();
        if (counts.size() != 1 || volumes.size() != 1) return proposal;
        var count = counts.get(0); var volume = volumes.get(0);
        if (!Objects.equals(count.value(), x.packAmount() + x.packUnit())) return proposal;
        var facts = new ArrayList<>(proposal.quantityEvidence().facts());
        var physicalFacts = facts.stream().filter(f -> f != null && f.role() == Role.MAIN
                && f.measure() == Measure.VOLUME && f.scope() == Scope.ITEM && optionId.equals(f.optionId())
                && x.itemAmount().equals(f.amount()) && x.itemUnit().equalsIgnoreCase(f.unit())
                && "productCompositionText".equals(f.source()) && f.quote() != null
                && f.quote().contains(x.itemQuote())).toList();
        var outerFacts = facts.stream().filter(f -> f != null && f.role() == Role.MAIN
                && f.measure() == Measure.COUNT && f.scope() == Scope.TOTAL && optionId.equals(f.optionId())
                && x.packAmount().equals(f.amount()) && x.packUnit().equals(f.unit())
                && "productCompositionText".equals(f.source()) && f.quote() != null
                && f.quote().contains(x.packAmount() + x.packUnit())).toList();
        if (physicalFacts.size() != 1 || outerFacts.size() != 1
                || !physicalFacts.get(0).componentId().equals(outerFacts.get(0).componentId())) return proposal;
        MappingProposal.Entry fixedCount = count;
        var countCalc = count.calculation();
        if (countCalc != null && countCalc.operation() == Calculation.Operation.PACK_COUNT
                && countCalc.operands() != null && countCalc.operands().isEmpty()
                && countCalc.context() != null && "productCompositionText".equals(countCalc.context().source())
                && x.quote().equals(countCalc.context().text())) {
            fixedCount = new MappingProposal.Entry(optionId, "수량", count.value(), count.confidence(),
                    "productCompositionText", x.packAmount() + x.packUnit(),
                    new Calculation(Calculation.Operation.PACK_COUNT, x.packUnit(),
                            List.of(new Calculation.Operand(x.packAmount(), x.packUnit(),
                                    new Calculation.Evidence("productCompositionText", x.packAmount() + x.packUnit()))),
                            countCalc.context()));
        }
        if (volume.calculation() != null && volume.calculation().operation() == Calculation.Operation.PACK_CAPACITY
                && Objects.equals(volume.value(), x.output())) {
            MappingProposal.Entry replacement = fixedCount;
            var entries = proposal.mappings().stream().map(e -> e == count ? replacement : e).toList();
            return new MappingProposal(proposal.certain(), proposal.confidence(), entries, proposal.reason(),
                    proposal.quantityEvidence());
        }
        if (!Objects.equals(volume.value(), x.itemAmount() + x.itemUnit())
                || volume.calculation() == null || volume.calculation().operation() != Calculation.Operation.DIRECT
                || volume.calculation().operands() == null || volume.calculation().operands().size() != 1) return proposal;
        var physicalOperand = volume.calculation().operands().get(0);
        if (physicalOperand == null || !x.itemAmount().equals(physicalOperand.amount())
                || !x.itemUnit().equalsIgnoreCase(physicalOperand.unit()) || physicalOperand.evidence() == null
                || !"productCompositionText".equals(physicalOperand.evidence().source())
                || physicalOperand.evidence().text() == null
                || !physicalOperand.evidence().text().contains(x.itemQuote())) return proposal;
        if (facts.stream().noneMatch(f -> f != null && f.role() == Role.MAIN && f.measure() == Measure.COUNT
                && f.scope() == Scope.PACK && optionId.equals(f.optionId())
                && physicalFacts.get(0).componentId().equals(f.componentId())
                && x.contentAmount().equals(f.amount()) && x.contentUnit().equals(f.unit())
                && "productCompositionText".equals(f.source()) && f.quote() != null
                && f.quote().contains(x.contentQuote()))) {
            String id = "server-pack-content";
            int suffix = 2;
            while (containsId(facts, id)) id = "server-pack-content-" + suffix++;
            facts.add(new Fact(id, optionId, physicalFacts.get(0).componentId(), Role.MAIN, Measure.COUNT,
                    Scope.PACK, x.contentAmount(), x.contentUnit(), "productCompositionText", x.contentQuote()));
        }
        var calculation = new Calculation(Calculation.Operation.PACK_CAPACITY, x.itemUnit(),
                List.of(physicalOperand, new Calculation.Operand(x.contentAmount(), x.contentUnit(),
                        new Calculation.Evidence("productCompositionText", x.contentQuote()))),
                new Calculation.Evidence("productCompositionText", x.quote()));
        var updated = new MappingProposal.Entry(optionId, "개당 용량", x.output(), volume.confidence(),
                "productCompositionText", x.quote(), calculation);
        MappingProposal.Entry replacement = fixedCount;
        var entries = proposal.mappings().stream().map(e -> e == volume ? updated : e == count ? replacement : e).toList();
        String reason = "판매 포장 하나의 용량 " + x.itemAmount() + x.itemUnit() + " × "
                + x.contentAmount() + x.contentUnit() + " = " + x.output() + "을 원문 구성에서 계산했습니다.";
        return new MappingProposal(proposal.certain(), proposal.confidence(), entries, reason,
                new QuantityEvidence(facts, null, proposal.quantityEvidence().sourceReviews()));
    }

    private static boolean containsId(List<Fact> facts, String id) {
        return facts.stream().anyMatch(f -> f != null && id.equals(f.id()));
    }
}
