package com.aaa.collector.stock.supply.correction;

/**
 * M6 레거시 {@code acml_vol} 백필 1회 실행 결과 집계 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 plan.md
 * §M6).
 *
 * @param corrected 정정 완료 행 수(MATCHED 또는 EVENT_ADJUSTED)
 * @param revisionSuspected REVISION_SUSPECTED로 스킵된 행 수(REQ-SSVC-053 — 자동 재시도 대상 유지)
 * @param skipped 그 외 사유(윈도우 응답에 대상 거래일 누락·TR04 재조회 실패·daily_ohlcv 미매칭 등)로 스킵된 행 수
 * @param windowFetchCount 이번 실행에서 수행한 TR04 기간 조회(윈도우) 호출 횟수 — 종목×기간 윈도우 청킹(plan.md §M6)이 행 수 대비 호출
 *     수를 줄였는지 관측하는 지표
 */
public record AcmlVolLegacyBackfillResult(
        int corrected, int revisionSuspected, int skipped, int windowFetchCount) {}
