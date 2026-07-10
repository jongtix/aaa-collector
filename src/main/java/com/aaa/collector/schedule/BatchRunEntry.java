package com.aaa.collector.schedule;

/**
 * 예상 실행(expected-run) 레지스트리의 단위 항목 (SPEC-COLLECTOR-EXPECTED-RUN-001 REQ-XR-002).
 *
 * <p>편입 배치별 {@code (label, cron, zone, marginSeconds)}를 단일 소스로 선언한다. {@code expected_run} 앵커는
 * {@code (cron, zone)}으로부터 {@link com.aaa.collector.schedule.catchup.ExpectedFireCalculator}가 산출하고,
 * {@code run_margin} 게이지는 {@code marginSeconds}를 노출한다(T-003에서 배선).
 *
 * @param label 배치 라벨 — 메트릭 {@code batch} 라벨과 동일, 레지스트리 내 유일
 * @param cron Spring cron 표현식(6필드)
 * @param zone 타임존 문자열(예: {@code Asia/Seoul}, {@code America/New_York})
 * @param marginSeconds 허용 마진(초) — 발화 시각 = 예상 실행 슬롯 + 마진(§9 시작 표)
 */
public record BatchRunEntry(String label, String cron, String zone, long marginSeconds) {}
