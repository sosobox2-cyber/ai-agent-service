package com.cware.ai.dto;

import java.util.List;

/** AI의 연산·피연산자 설명. 수량 계열은 출처·단위 동치·계층을 검사하되 일반 SUM은 재계산하지 않는다. */
public record Calculation(Operation operation, String outputUnit, List<Operand> operands, Evidence context) {
    // 직접 추출, 단위 환산, 합산, 판매 묶음 수, 묶음 내 수량을 구분한다.
    public enum Operation { DIRECT, CONVERT, SUM, PACK_COUNT, PACK_CONTENT }
    public record Evidence(String source, String text) {}
    public record Operand(String amount, String unit, Evidence evidence) {}
}
