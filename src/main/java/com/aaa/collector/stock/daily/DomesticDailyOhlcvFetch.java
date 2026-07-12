package com.aaa.collector.stock.daily;

import java.time.LocalDate;
import java.util.List;

/**
 * 국내 일봉 백필 fetch 단계 결과 DTO.
 *
 * <p>REQ-INSERT-002: 검증 단계에서 파싱된 {@link ParsedOhlcvRow} 목록을 전달하여 persist 단계가 재파싱 없이 바인딩한다 (W-1
 * 불변식).
 *
 * @param rows 검증 통과(저장 대상) 행 목록
 * @param oldestTradeDate 저장 대상 행들의 최소 거래일 (없으면 {@code null})
 * @param rowCount 저장 대상 행 수 ({@code rows.size()}) — 메트릭·last_row_count·oldest 산정의 입력(의미 보존)
 * @param rawRowCount KIS 원본 응답 행수({@code modYn="Y"} 제외 후·검증 거부 전) — GROUP_A 종료 판정 전용 입력
 * @param rawOldestTradeDate 검증 거부 전 원본 응답 전 행(가격=0 등 거부 행 포함)의 최소 거래일 (파싱 불가 시 {@code null}) —
 *     aaa-infra#97: 아카이브 꼬리(상장일 이전 쓰레기 행)의 케이스②③ exhaustion probe 트리거 전용 입력
 */
// @MX:NOTE: [AUTO] rawRowCount = KIS 원본 응답 행수(거부 전). GROUP_A 종료 판정 전용 신호로 rowCount(저장 행수)와 분리.
// @MX:REASON: SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-081,-082,-088 — 거래정지 거부가 종료 입력을 깎아
// 분할 구간을 오종료하던 결함 차단. rowCount는 저장·메트릭·last_row_count로 의미 보존.
public record DomesticDailyOhlcvFetch(
        List<ParsedOhlcvRow> rows,
        LocalDate oldestTradeDate,
        int rowCount,
        int rawRowCount,
        LocalDate rawOldestTradeDate) {

    public DomesticDailyOhlcvFetch {
        rows = List.copyOf(rows);
    }

    /** 4-인자 레거시 호출부 호환 — {@code rawOldestTradeDate=null}(미산정)로 위임한다 (aaa-infra#97). */
    public DomesticDailyOhlcvFetch(
            List<ParsedOhlcvRow> rows, LocalDate oldestTradeDate, int rowCount, int rawRowCount) {
        this(rows, oldestTradeDate, rowCount, rawRowCount, null);
    }
}
