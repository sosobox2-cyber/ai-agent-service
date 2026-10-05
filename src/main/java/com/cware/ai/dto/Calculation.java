package com.cware.ai.dto;

import java.util.List;

/** AI가 제안하는 제한된 연산. 서버가 원문과 피연산자를 대조하고 재계산한다. */
public record Calculation(Operation operation, String outputUnit, List<Operand> operands, Evidence context) {
    public enum Operation { DIRECT, CONVERT, SUM, PACK_COUNT, PACK_CONTENT }
    public record Evidence(String source, String text) {}
    public record Operand(String amount, String unit, Evidence evidence) {}
}
