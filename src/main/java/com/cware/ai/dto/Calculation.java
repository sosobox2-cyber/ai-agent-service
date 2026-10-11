package com.cware.ai.dto;

import java.util.List;

/** AI가 제출한 수량 판단의 연산·피연산자·근거 설명. 최종 승인 흐름은 이를 재계산하지 않는다. */
public record Calculation(Operation operation, String outputUnit, List<Operand> operands, Evidence context) {
    // 직접 추출, 단위 환산, 합산, 판매 묶음 수, 묶음 내 수량을 구분한다.
    public enum Operation { DIRECT, CONVERT, SUM, PACK_COUNT, PACK_CONTENT }
    public record Evidence(String source, String text) {}
    public record Operand(String amount, String unit, Evidence evidence) {}
}
