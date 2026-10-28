package com.cware.ai;

import com.cware.ai.config.InferenceProperties;
import com.cware.ai.dto.*;
import com.cware.ai.inference.MappingProposal;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import com.cware.ai.dto.QuantityEvidence.*;

/** 회귀 테스트에서 공유하는 상품 요청, 정상·불확실 제안과 추론 설정을 생성한다. */
final class Fixtures {
    /** 기존 정상 대역을 v29 계약으로 옮기는 테스트 빌더. 운영 추출 코드에서는 사용하지 않는다. */
    static MappingProposal grounded(InferenceRequest request, MappingProposal proposal) {
        if (proposal.quantityEvidence() != null) return proposal;
        var facts = new ArrayList<Fact>();
        var bindings = new ArrayList<Binding>();
        var entries = new ArrayList<MappingProposal.Entry>();
        for (var entry : proposal.mappings()) {
            String target = entry.targetPurchaseOptionName();
            if (!com.cware.ai.inference.QuantityEvidenceValidator.TARGETS.contains(target)) { entries.add(entry); continue; }
            Measure measure = target.equals("개당 중량") ? Measure.WEIGHT : target.equals("개당 용량") ? Measure.VOLUME : Measure.COUNT;
            Scope scope = target.equals("수량") ? Scope.TOTAL : target.equals("개당 수량") ? Scope.PACK : Scope.ITEM;
            var calc = entry.calculation();
            if (calc == null) {
                String source = entry.evidenceSource(), quote = entry.evidenceText();
                if (source == null || quote == null) {
                    source = "optionName1";
                    quote = request.options().stream().filter(o -> o.optionId().equals(entry.optionId())).findFirst().orElseThrow().optionName1();
                    if (!quote.matches(".*[0-9].*")) { source = "goodsName"; quote = request.goodsName(); }
                }
                var match = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([^0-9\\s]+)").matcher(entry.value());
                if (!match.matches()) throw new IllegalArgumentException("fixture value: " + entry.value());
                String amount = match.group(1), output = match.group(2), unit = output;
                var original = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(amount) + "\\s*(박스|세트|팩|롤|병|매|패치|개|ml|g|kg)").matcher(quote);
                if (original.find()) unit = original.group(1);
                boolean singleSet = entry.value().equals("1세트") && !quote.contains("1세트");
                var operation = singleSet ? Calculation.Operation.PACK_COUNT : scope == Scope.PACK ? Calculation.Operation.PACK_CONTENT : Calculation.Operation.DIRECT;
                calc = new Calculation(operation, output, singleSet ? List.of() : List.of(new Calculation.Operand(amount, unit, new Calculation.Evidence(source, quote))), null);
            }
            var ids = new ArrayList<String>();
            for (var operand : calc.operands()) {
                String id = "q" + facts.size(); ids.add(id);
                facts.add(new Fact(id, entry.optionId(), "main", Role.MAIN, measure, scope, operand.amount(), operand.unit(),
                        operand.evidence().source(), operand.evidence().text()));
            }
            if (calc.operands().isEmpty()) {
                String id = "q" + facts.size(); ids.add(id);
                var evidence = calc.context() != null ? calc.context() : new Calculation.Evidence(entry.evidenceSource(), entry.evidenceText());
                facts.add(new Fact(id, entry.optionId(), "main", Role.MAIN, measure, scope, null, "세트", evidence.source(), evidence.text()));
            }
            bindings.add(new Binding(entry.optionId(), target, "main", scope, ids));
            entries.add(new MappingProposal.Entry(entry.optionId(), target, entry.value(), entry.confidence(), entry.evidenceSource(), entry.evidenceText(), calc));
        }
        return new MappingProposal(proposal.certain(), proposal.confidence(), entries, proposal.reason(), QuantityEvidenceTest.context(request, facts, bindings));
    }
    static com.cware.ai.inference.AiUsageLogger usageLogger(boolean enabled, java.nio.file.Path path) {
        var pricing = new com.cware.ai.config.AiUsagePricing();
        pricing.setModels(java.util.Map.of("gpt-4.1-mini", new com.cware.ai.config.AiUsagePricing.Rates(
                new java.math.BigDecimal("0.40"), new java.math.BigDecimal("0.10"), new java.math.BigDecimal("1.60"))));
        return new com.cware.ai.inference.AiUsageLogger(enabled, path,
                new com.fasterxml.jackson.databind.ObjectMapper(), pricing, 30, "1GB");
    }
    static InferenceRequest request() {
        return new InferenceRequest("100000123","남성 배기핏 바지","패션 > 남성의류",
                List.of("핏","색상","사이즈"),List.of(
                new SourceOption("1","배기핏 남색 100 "),
                new SourceOption("2","배기핏 남색 150"),
                new SourceOption("3","배기핏 남색 200")), "제품 소재: 면");
    }
    static InferenceRequest ambiguous() {
        var base = request();
        return new InferenceRequest(base.goodsId(),base.goodsName(),base.categoryName(),List.of("색상","사이즈"),
                List.of(new SourceOption("1","남색 100")), base.productNoticeText());
    }
    static InferenceRequest withOptions(InferenceRequest r,List<SourceOption> options) {
        return new InferenceRequest(r.goodsId(),r.goodsName(),r.categoryName(),r.allowedPurchaseOptions(),options,r.productNoticeText());
    }
    static InferenceRequest withNotice(InferenceRequest r, String text) {
        return new InferenceRequest(r.goodsId(),r.goodsName(),r.categoryName(),r.allowedPurchaseOptions(),r.options(),text);
    }
    static MappingProposal proposal() {
        return new MappingProposal(true,.99,List.of(
                new MappingProposal.Entry("1","핏","배기핏",.99),
                new MappingProposal.Entry("1","색상","남색",.99),
                new MappingProposal.Entry("1","사이즈","100",.99),
                new MappingProposal.Entry("2","핏","배기핏",.99),
                new MappingProposal.Entry("2","색상","남색",.99),
                new MappingProposal.Entry("2","사이즈","150",.99),
                new MappingProposal.Entry("3","핏","배기핏",.99),
                new MappingProposal.Entry("3","색상","남색",.99),
                new MappingProposal.Entry("3","사이즈","200",.99)),"명확하게 매핑 가능");
    }
    static MappingProposal ambiguousProposal() {
        return new MappingProposal(true,.99,List.of(
                new MappingProposal.Entry("1","색상","남색",.99),
                new MappingProposal.Entry("1","사이즈","100",.99)),"명확하게 매핑 가능");
    }
    static InferenceProperties properties() {
        return new InferenceProperties(.80,"coupang-option-v6",Duration.ofSeconds(5),Duration.ofSeconds(30));
    }
}
