package com.aaa.collector.stock.exthours;

/** 미국 시간외 거래 세션 구분 (SPEC-COLLECTOR-EXTHOURS-001). */
public enum Session {
    /** 장전 거래 (Pre-Market: 04:00–09:30 ET). */
    PRE,
    /** 장후 거래 (After-Hours: 16:00–20:00 ET). */
    AFTER
}
