package com.aaa.collector.stock.daily;

import java.time.LocalDate;
import java.util.List;

/**
 * 미국(해외) 일봉 백필 fetch 단계 결과 DTO.
 *
 * <p>REQ-INSERT-005: 검증 단계에서 파싱된 {@link ParsedOhlcvRow} 목록을 전달하여 persist 단계가 재파싱 없이 바인딩한다 (W-1
 * 불변식).
 *
 * @param rows 검증 통과(저장 대상) 행 목록
 * @param oldestTradeDate 저장 대상 행들의 최소 거래일 (없으면 {@code null})
 * @param rowCount 저장 대상 행 수 ({@code kept.size()}) — 메트릭·oldest 산정의 입력(의미 보존)
 * @param rawRowCount KIS 원본 응답 행수(ET 당일 가드 제외 후·검증 거부 전, mod_yn 부재) — GROUP_A 종료 판정 전용
 * @param rawOldestTradeDate 검증 거부 전 원본 응답 전 행(가격=0 등 거부 행 포함)의 최소 거래일 (파싱 불가 시 {@code null}) —
 *     aaa-infra#97: 아카이브 꼬리(상장일 이전 쓰레기 행)의 케이스②③ exhaustion probe 트리거 전용 입력
 */
// @MX:NOTE: [AUTO] rawRowCount = KIS 원본 응답 행수(ET 당일 제외 후·거부 전). GROUP_A 종료 판정 전용, rowCount와 분리.
// @MX:REASON: SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-091,-088 — 비분할 거래정지 거부가 종료를 흔들지 않게 차단.
public record OverseasDailyOhlcvFetch(
        List<ParsedOhlcvRow> rows,
        LocalDate oldestTradeDate,
        int rowCount,
        int rawRowCount,
        LocalDate rawOldestTradeDate) {

    public OverseasDailyOhlcvFetch {
        rows = List.copyOf(rows);
    }

    /** 4-인자 레거시 호출부 호환 — {@code rawOldestTradeDate=null}(미산정)로 위임한다 (aaa-infra#97). */
    public OverseasDailyOhlcvFetch(
            List<ParsedOhlcvRow> rows, LocalDate oldestTradeDate, int rowCount, int rawRowCount) {
        this(rows, oldestTradeDate, rowCount, rawRowCount, null);
    }
}
