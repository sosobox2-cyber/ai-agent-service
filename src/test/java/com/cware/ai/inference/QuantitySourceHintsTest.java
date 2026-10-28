package com.cware.ai.inference;

import com.cware.ai.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class QuantitySourceHintsTest {
    @Test void extractsLiteralPackAndContentCandidatesWithoutTreatingLengthAsVolume() {
        var request = new InferenceRequest("hint", "점보롤 250M x 4롤 x 4팩", null,
                List.of("수량", "개당 수량", "길이"), List.of(new SourceOption("1", "단일상품")),
                "상품 정보", List.of(), "점보롤 250M x 4롤 x 4팩");
        var hints = QuantitySourceHints.from(request);
        assertThat(hints).extracting(QuantitySourceHints.Hint::quote).contains("4롤", "4팩").doesNotContain("250M");
        assertThat(hints).anyMatch(h -> h.source().equals("goodsName") && h.quote().equals("4롤") && h.suggestedScope().equals("PACK"));
        assertThat(hints).anyMatch(h -> h.source().equals("productCompositionText") && h.quote().equals("4팩") && h.suggestedScope().equals("TOTAL"));
    }

    @Test void keepsBothSidesOfPackConflictAndDoesNotSendHintsForLight() {
        var request = new InferenceRequest("hint", "물티슈 9매x4팩", null,
                List.of("수량", "개당 수량"), List.of(new SourceOption("1", "단일상품")),
                "상품 정보", List.of(), "물티슈 9매x3팩");
        var hints = QuantitySourceHints.from(request);
        assertThat(hints).extracting(QuantitySourceHints.Hint::quote).contains("9매", "4팩", "3팩");
        assertThat(QuantitySourceHints.from(new InferenceRequest("hint", "검은 티셔츠", null,
                List.of("색상"), List.of(new SourceOption("1", "블랙")), "상품 정보"))).isEmpty();
    }
}
