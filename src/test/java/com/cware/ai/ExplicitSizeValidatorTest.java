package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.inference.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ExplicitSizeValidatorTest {
    private InferenceRequest request(String target, String name, String option, String notice) {
        return new InferenceRequest("size-fixture", name, null, List.of("색상", target),
                List.of(new SourceOption("1", option)), notice, List.of());
    }
    private MappingProposal proposal(String target, String size) {
        var entries = new ArrayList<MappingProposal.Entry>();
        entries.add(new MappingProposal.Entry("1", "색상", "브라운", .95));
        if (size != null) entries.add(new MappingProposal.Entry("1", target, size, .95));
        return new MappingProposal(true, .95, entries, "고정 사이즈 제안");
    }
    @ParameterizedTest
    @CsvSource({"싱글,S", "슈퍼싱글,SS", "퀸,Q", "킹,K", "슈퍼킹,SK", "라지킹,LK", "더블,D", "패밀리,FAMILY"})
    void requiresUniqueLabelAndAcceptsAliases(String name, String alias) {
        var r = request("침구류사이즈", "침대 " + name, "브라운", "제품 치수 W1720mm");
        var absent = AiValueContractTest.assess(r, proposal("침구류사이즈", null));
        assertThat(absent.success()).isFalse();
        assertThat(absent.validationErrors()).anyMatch(e -> e.startsWith("SIZE_VALUE_REQUIRED"));
        for (String bad : List.of("W1720mm L2260mm", "퀸/킹", "FREE")) {
            var result = AiValueContractTest.assess(r, proposal("침구류사이즈", bad));
            assertThat(result.success()).isFalse();
            assertThat(result.validationErrors()).anyMatch(e -> e.startsWith("SIZE_VALUE_INVALID"));
            assertThat(result.optionMappings()).isEmpty();
            assertThat(result.aiAssessment().mappings().get(1).value()).isEqualTo(bad);
        }
        assertThat(AiValueContractTest.assess(r, proposal("침구류사이즈", name + "(" + alias + ")")).success()).isTrue();
        assertThat(AiValueContractTest.assess(r, proposal("침구류사이즈", alias)).success()).isTrue();
    }
    @Test void preservesFreeGarmentAliasesAndExplicitDiaperSize() {
        for (String[] row : List.of(new String[]{"FREE", "FREE"}, new String[]{"블랙/55/S", "55/S"}, new String[]{"화이트/S(90)", "S(90)"})) {
            var r = request("패션의류/잡화 사이즈", "가방 또는 의류", row[0], "검증용 정보");
            assertThat(AiValueContractTest.assess(r, proposal("패션의류/잡화 사이즈", row[1])).success()).isTrue();
            assertThat(AiValueContractTest.assess(r, proposal("패션의류/잡화 사이즈", null)).success()).isFalse();
        }
        var r = request("성인기저귀/패드 사이즈", "성인기저귀 대형 20매", "없음", "정보");
        assertThat(AiValueContractTest.assess(r, proposal("성인기저귀/패드 사이즈", null)).success()).isFalse();
        assertThat(AiValueContractTest.assess(r, proposal("성인기저귀/패드 사이즈", "대형")).success()).isTrue();
    }
    @Test void doesNotInferFromNoticeOrForceUnknownAndAmbiguousLabels() {
        for (String[] row : List.of(new String[]{"카우치", "없음"}, new String[]{"침대 퀸/킹", "브라운"},
                new String[]{"침대", "싱글/퀸"}, new String[]{"MODEL-S100 세정제", "없음"},
                new String[]{"[브랜드](SK) 냉감패드 세트", "블루"})) {
            var r = request("사이즈", row[0], row[1], "싱글(S), 슈퍼싱글(SS), 퀸(Q), 패드 슈퍼킹");
            assertThat(AiValueContractTest.assess(r, proposal("사이즈", null)).success()).as(Arrays.toString(row)).isTrue();
        }
    }
    @Test void preservesNoticeDimensionsForMatAndBag() {
        var mat = request("전기/온수매트 사이즈", "카본 매트 더블", "없음", "크기:1400*1800 (± 20mm)");
        assertThat(AiValueContractTest.assess(mat, proposal("전기/온수매트 사이즈", "1400*1800 (± 20mm)")).success()).isTrue();
        assertThat(AiValueContractTest.assess(mat, proposal("전기/온수매트 사이즈", "900*1200mm")).success()).isFalse();
        var bag = request("패션의류/잡화 사이즈", "크로스백", "오렌지", "폭 33cm / 높이 20cm");
        assertThat(AiValueContractTest.assess(bag, proposal("패션의류/잡화 사이즈", "33x20cm")).success()).isTrue();
        assertThat(AiValueContractTest.assess(bag, proposal("패션의류/잡화 사이즈", null)).success()).isTrue();
    }
    @Test void usesOptionSizeButDoesNotGuessUnassignedOptionsFromCommonName() {
        var r = new InferenceRequest("size-mixed", "침대 퀸", null, List.of("색상", "침구류사이즈"),
                List.of(new SourceOption("1", "킹"), new SourceOption("2", "브라운")), "정보", List.of());
        var p = new MappingProposal(true, .95, List.of(new MappingProposal.Entry("1", "색상", "블랙", .95),
                new MappingProposal.Entry("1", "침구류사이즈", "킹", .95),
                new MappingProposal.Entry("2", "색상", "브라운", .95)), "명확한 단품만 판단");
        assertThat(AiValueContractTest.assess(r, p).success()).isTrue();
    }
}
