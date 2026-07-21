package com.aaa.collector.stock.enums;

/**
 * 종목 기본정보 조회 시점에 감지된 상장 상태 (SPEC-COLLECTOR-WLSYNC-008 REQ-WLSYNC-142,143).
 *
 * <p>영속 컬럼이 아니라 {@code StockInfoParser} → {@code StockInfo} → {@code Stock} 상태 전이 메서드로 이어지는 파싱
 * 파이프라인 전달용 값이다. 실제 영속은 {@code Stock.active}(boolean)와 {@code Stock.delistedAt}(사유 메타데이터)이 담당한다.
 */
public enum ListingStatus {
    /** 정상 거래 중 */
    NORMAL,
    /** 거래정지 — 가역 */
    HALTED,
    /** 상장폐지 — 비가역(set-only) */
    DELISTED
}
