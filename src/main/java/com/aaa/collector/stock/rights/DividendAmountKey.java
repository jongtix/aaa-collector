package com.aaa.collector.stock.rights;

import java.time.LocalDate;

/**
 * CTRGT011R 금액 맵 키 — {@code (symbol, acpl_bass_dt)} (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001
 * REQ-ODA-014, 015 [HARD] — {@code bass_dt}가 아닌 {@code acpl_bass_dt}로 구성한다).
 */
record DividendAmountKey(String symbol, LocalDate acplBassDt) {}
