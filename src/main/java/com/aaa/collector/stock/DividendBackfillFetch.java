package com.aaa.collector.stock;

import java.time.LocalDate;
import java.util.List;

/**
 * 국내 현금배당(DIVIDEND) 종목지정 과거 백필 fetch 단계 결과 DTO (SPEC-COLLECTOR-BACKFILL-009 W2).
 *
 * <p>액면교체(SPLIT) 백필의 {@link RevSplitBackfillFetch}와 대칭으로, {@code
 * BackfillWindowExecutor.routePersist} switch의 타입 매칭 대상이다. fetch 단계에서 {@link
 * DividendRowAccumulator}가 검증·매핑·0/0 defer(RD-6)·rate-only 원본 저장(RD-7)을 마친 적재 대상 엔티티를 담아, persist
 * 단계가 재매핑 없이 INSERT IGNORE로 적재한다. SPLIT과 구분되는 별도 DTO 타입이므로 {@code routePersist} switch가 두 백필 경로를 오염
 * 없이 분기한다.
 *
 * <p>[HARD] REQ-BACKFILL-135 — 두 행수를 명시적으로 분리한다.
 *
 * <ul>
 *   <li>{@code rawRowCount}: KIS {@code output1} 원본 응답 행수(defer/검증 skip 적용 <b>전</b>). GROUP_A 종료 판정
 *       입력 — 100행 미만이면 그 종목 COMPLETED, 100행 도달이면 IN_PROGRESS 유지(다음 회차 이월).
 *   <li>{@code validRows}: 0/0 defer·검증 skip을 거쳐 실제 적재되는 엔티티(rate-only 행 포함). {@code
 *       validRows.size()}는 종료 판정과 분리된 저장 행수다.
 * </ul>
 *
 * <p>2026-07-05 실측(6종목 전부 71행 이하·{@code tr_cont: E})상 종목별 전기간 조회는 100행 미만으로 완결되어, 첫 윈도우 GROUP_A
 * COMPLETED가 두 값의 발산과 무관하게 성립한다(RD-2).
 *
 * <p>[SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-012] {@code rawOldestRecordDate}는 검증 통과분({@code
 * validRows})이 아니라 <b>원본 응답 전체</b>({@code output1}, defer 이전)의 최소 {@code record_date}다. 한 윈도우의
 * 최고(最古) 행들이 전부 0/0 defer되어 {@code validRows}에서 사라져도, anchor 전진 입력은 defer 여부와 무관하게 원본 최소값을 사용해 무전진
 * 오판(anchor stall)을 방지한다(BACKFILL-006 "종료 입력=원본 행수" 원칙의 전진판). 옛 {@code oldestRecordDate}(적재 대상 기준)
 * 필드는 이 필드로 완전히 대체됐다.
 *
 * @param validRows 적재 대상(0/0 defer·검증 skip 적용 후) 엔티티
 * @param rawOldestRecordDate 원본 응답 전체(defer 이전) 행들의 최소 {@code record_date}, 원본 응답이 비었으면 {@code
 *     null}
 * @param rawRowCount KIS {@code output1} 원본 응답 행수(skip 전) — GROUP_A 종료 판정 입력(REQ-BACKFILL-135)
 */
public record DividendBackfillFetch(
        List<CorporateEvent> validRows, LocalDate rawOldestRecordDate, int rawRowCount) {

    public DividendBackfillFetch {
        validRows = List.copyOf(validRows);
    }
}
