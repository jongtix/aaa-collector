package com.aaa.collector.stock.enums;

/** 상장폐지 사유 (SPEC-COLLECTOR-WLSYNC-008 REQ-WLSYNC-140). */
public enum DelistingReason {
    /** 파산 */
    BANKRUPTCY,
    /** 흡수합병 */
    MERGER,
    /** 자진상폐 */
    VOLUNTARY,
    /** ETF 상장폐지(청산) */
    ETF_TERMINATION,
    /** 사유 미상 — 감지 시점 초기값. DART 공시 기반 자동 분류는 스코프 밖(후속 이슈로 분리) */
    UNKNOWN
}
