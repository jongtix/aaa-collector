package com.aaa.collector.stock.supply.correction;

/**
 * T+0 예비치 소급 정정(근본원인 B, aaa-infra#133) 1회 실행 결과 집계 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001
 * REQ-T0R-010~012, -020~022, -030).
 *
 * @param corrected KIS TR04 라이브 재조회 확정치로 {@code short_sell_qty}·{@code short_sell_vol_rate}를 갱신 완료한
 *     행 수
 * @param skipped TR04 재조회 실패·응답 없음(상장폐지 등) 등으로 스킵된 행 수
 */
public record ShortSaleT0RevisionCorrectionResult(int corrected, int skipped) {}
