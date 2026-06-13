package com.aaa.collector.stock.supply;

import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 14일 윈도우 경계 커버리지 관측 헬퍼 (REQ-BATCH2-025, 3종 공통).
 *
 * <p>종목별 단일 응답이 반환한 행들의 <b>가장 오래된 {@code trade_date}(최소 trade_date)</b>가 윈도우 시작일({@code today −
 * LOOKBACK_CALENDAR_DAYS})보다 늦으면(= 단일 응답이 윈도우 하단을 미커버) WARN 로그를 남긴다. 이는 "단일 응답이 윈도우 하단까지 커버한다"는
 * 가정(OI-3)이 깨졌음을 조기 감지하기 위한 관측 안전장치다(향후 {@code tr_cont} 페이징 도입 판단 트리거).
 *
 * <p><b>판정은 순수 캘린더 날짜 비교</b>다(거래일 환산·거래일 캘린더 의존 없음). 최소 trade_date ≤ 윈도우 시작일이면 하단 커버(WARN 없음), 최소
 * trade_date {@literal >} 윈도우 시작일이면 하단 미커버(WARN).
 *
 * <p>빈 응답(반환 행 0건)은 최소 trade_date가 존재하지 않으므로 본 검사 대상이 아니며 REQ-BATCH2-063의 정상 no-op으로 분류한다.
 *
 * <p><b>[과탐 허용 — N1]</b> 윈도우 시작일({@code today−14}캘린더일)이 주말/공휴일이면 정상 데이터(직전 거래일까지만 존재)도 최소
 * trade_date {@literal >} 시작일이 되어 WARN이 발화할 수 있다. REQ-025는 과탐을 허용하고 수집을 중단하지 않는 설계이므로(거래일 캘린더 의존을
 * 들이지 않기 위해 시작일 보정은 하지 않는다) 이 WARN은 오탐일 수 있다 — 운영자는 누적 추이로 실제 구조적 하단 잘림 여부를 판단한다.
 *
 * <p>WARN은 수집을 중단시키지 않으며, 반환된 데이터는 호출자의 멱등 저장 로직이 정상 저장한다(본 헬퍼는 관측만 담당).
 */
@Slf4j
final class WindowCoverageChecker {

    private WindowCoverageChecker() {}

    /**
     * 반환 행들의 최소 {@code trade_date}와 윈도우 시작일을 비교하여 하단 미커버 시 WARN을 남긴다.
     *
     * @param kind 종(種) 식별자 (로그용: investor/short-sale/credit-balance)
     * @param symbol 종목 코드
     * @param tradeDates 단일 응답에서 파싱된 거래일 목록 (윈도우 필터 전, 빈 목록이면 no-op)
     * @param windowStart 기대 윈도우 시작일 ({@code today − LOOKBACK_CALENDAR_DAYS})
     */
    static void check(
            String kind, String symbol, List<LocalDate> tradeDates, LocalDate windowStart) {
        if (tradeDates.isEmpty()) {
            return;
        }

        LocalDate minTradeDate = tradeDates.stream().min(LocalDate::compareTo).orElseThrow();
        if (minTradeDate.isAfter(windowStart)) {
            log.warn(
                    "[{}] 경계 커버리지 미충족 (단일 응답 윈도우 하단 미커버 — 과탐 가능, OI-3 가정 위반 관측) "
                            + "— symbol={}, 기대 윈도우 시작일={}, 실제 최소 trade_date={}",
                    kind,
                    symbol,
                    windowStart,
                    minTradeDate);
        }
    }
}
