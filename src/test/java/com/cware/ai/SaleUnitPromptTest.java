package com.cware.ai;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** FULL 지침과 기존 계약의 양립 여부를 확인한다. 모델 생성 정확도 테스트는 아니다. */
class SaleUnitPromptTest {
    @Test void fullDefinesSaleUnitWithoutExpandingLightOrWeightCalculation() throws Exception {
        String full;
        try (var stream = getClass().getResourceAsStream("/prompts/coupang-purchase-option-system.txt")) {
            full = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(full).contains("개당 중량/용량은 판매 단위 하나", "40g x 30포 x 3박스", "1200g", "480g",
                "PACK_CAPACITY는 용량 전용", "현재 Schema", "원문 fact", "수산물 중량이라는 별도 대상");
        String light;
        try (var stream = getClass().getResourceAsStream("/prompts/coupang-purchase-option-system-light.txt")) {
            light = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(light).doesNotContain("40g x 30포 x 3박스", "1200g");
    }
}
