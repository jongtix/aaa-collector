package com.aaa.collector.market.indicator.usdkrw;

import com.aaa.collector.common.exception.CollectorException;

/**
 * KOREAEXIM 환율 API 쿼터 소진/차단(`result:4`) 전용 예외 (SPEC-COLLECTOR-MARKETIND-004 REQ-010, REQ-012).
 *
 * <p>KOREAEXIM 원본 응답이 전 필드 null인 1원소 배열이고 그 {@code result} 코드가 {@code 4}인 경우에 던져진다. 정상 빈 결과(길이 0 배열
 * {@code []})와 명확히 구분하기 위한 신호다. 백필 전용 조회 메서드({@link
 * KoreaeximExchangeRateClient#fetchDailyForBackfill(java.time.LocalDate)})는 체인을 경유하지 않고 호출자(백필
 * 오케스트레이터)까지 직접 전파된다. 라이브 {@link KoreaeximExchangeRateClient#fetchDaily(java.time.LocalDate)}도
 * (SPEC-COLLECTOR-MARKETIND-005 TASK-B부터) 동일하게 이 예외를 던지며, {@code MarketIndicatorSourceChain}의 기존
 * catch가 이를 흡수해 Yahoo로 폴백(reason="error")한다 — 체인 코드는 무수정이다.
 */
// @MX:NOTE: [AUTO] 소스 체인(MarketIndicatorSourceChain)에서 삼켜지지 않고 백필 오케스트레이터까지 그대로 전파돼야
// 한다 — fetchDailyForBackfill이 체인을 경유하지 않고 오케스트레이터가 직접 호출하는 구조이므로(REQ-012), 체인의
// Yahoo 폴백 흡수 경로 자체가 없다. 오케스트레이터는 이 예외를 캡(REQ-022)과 동급의 백스톱으로 받아 진행점을
// IN_PROGRESS 저장 후 그 회차 backward walk를 중단한다(REQ-023).
// @MX:SPEC: SPEC-COLLECTOR-MARKETIND-004 REQ-MARKETIND4-012
public class KoreaeximQuotaExhaustedException extends CollectorException {

    /**
     * @param message 쿼터 소진 시점의 조회 날짜 등 상세 메시지
     */
    public KoreaeximQuotaExhaustedException(String message) {
        super(message);
    }
}
