package com.aaa.collector.market.indicator.usdkrw;

import com.aaa.collector.common.exception.CollectorException;

/**
 * KOREAEXIM 환율 API 쿼터 소진/차단(`result:4`) 전용 예외 (SPEC-COLLECTOR-MARKETIND-004 REQ-010, REQ-012).
 *
 * <p>KOREAEXIM 원본 응답이 전 필드 null인 1원소 배열이고 그 {@code result} 코드가 {@code 4}인 경우에 던져진다. 정상 빈 결과(길이 0 배열
 * {@code []})와 명확히 구분하기 위한 신호로, 백필 전용 조회 메서드({@link
 * KoreaeximExchangeRateClient#fetchDailyForBackfill(java.time.LocalDate)})에 한정해 던져지며 호출자(백필
 * 오케스트레이터)까지 체인을 경유하지 않고 직접 전파된다. 라이브 {@link
 * KoreaeximExchangeRateClient#fetchDaily(java.time.LocalDate)}는 이 예외를 던지지 않고 result:4를 계속 빈 결과로
 * 취급한다(empty-retry·체인 폴백 유지, 무회귀).
 */
public class KoreaeximQuotaExhaustedException extends CollectorException {

    /**
     * @param message 쿼터 소진 시점의 조회 날짜 등 상세 메시지
     */
    public KoreaeximQuotaExhaustedException(String message) {
        super(message);
    }
}
