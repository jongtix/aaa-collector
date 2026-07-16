package com.aaa.collector.stock.shortsale.overseas.backfill;

/**
 * 하루치 FINRA CDN 파일 적재 결과 (REQ-BACKFILL-123 관측성 + SPEC-COLLECTOR-BACKFILL-011 §2.6 kept/raw).
 *
 * <p>{@link FinraCdnShortSaleBackfillOrchestrator#loadDate}의 반환 타입이며, {@link FinraCdnDailyLoader}를
 * 통해 {@link FinraCdnCoveredGapFiller}가 소비한다. 최상위 타입으로 분리해 오케스트레이터·필러·러너 사이의 결합을 record 값 타입 하나로
 * 좁힌다(코드리뷰 — PMD CouplingBetweenObjects 완화, 억제 주석 대신 구조 추출).
 *
 * @param kept 저장 시도가 예외 없이 완료된 매칭 심볼 수(§2.6 kept, 중복 삽입 시도 포함)
 * @param raw 병합 전 파일별 파싱 성공 행수 합(§2.6 raw, TASK-005a 후보 A)
 * @param skipped 파싱 skip 수(요약 행·빈값·음수·컬럼 부족)
 * @param unmatched 매칭 실패 심볼 수(symbolMap에 없는 심볼)
 */
public record FinraCdnDailyLoadOutcome(int kept, int raw, int skipped, int unmatched) {}
