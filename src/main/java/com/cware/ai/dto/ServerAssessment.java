package com.cware.ai.dto;

/** 서버가 적용·보류한 이유와 최종 적용 신뢰도 기준을 AI 판단과 구분해 전달한다. */
public record ServerAssessment(String decisionCode, String reason, Double confidenceThreshold) {}
