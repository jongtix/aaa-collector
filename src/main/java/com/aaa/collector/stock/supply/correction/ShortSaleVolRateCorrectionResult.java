package com.aaa.collector.stock.supply.correction;

/**
 * Track 1/Track 2 정정 배치 1회 실행 결과 집계 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001).
 *
 * @param corrected 정정(Track 1) 또는 검증(Track 2) 완료 행 수
 * @param revisionSuspected REVISION_SUSPECTED로 스킵된 행 수(Track 1 전용 — Track 2는 가드를 호출하지 않으므로 항상 0,
 *     REQ-SSVC-038/-057)
 * @param skipped 그 외 사유(TR04 재조회 실패·daily_ohlcv 미매칭 등)로 스킵된 행 수
 */
public record ShortSaleVolRateCorrectionResult(int corrected, int revisionSuspected, int skipped) {}
