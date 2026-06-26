package com.aaa.collector.schedule.catchup;

/**
 * catch-up 판정 결과 (SPEC-COLLECTOR-CATCHUP-001 T4).
 *
 * @param unitName 판정 대상 배치 단위명
 * @param shouldRun 재실행 여부
 * @param reason 판정 근거 (로깅용)
 */
public record CatchUpDecision(String unitName, boolean shouldRun, String reason) {}
