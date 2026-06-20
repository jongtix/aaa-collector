package com.aaa.collector.market.indicator;

import java.time.LocalDate;
import java.util.List;

/**
 * 시장 지표 외부 소스 포트 (SPEC-COLLECTOR-MARKETIND-001).
 *
 * <p>각 소스(CBOE, FRED, Yahoo, KOREAEXIM 등)는 이 포트를 구현한다. {@link MarketIndicatorSourceChain}이 Fallback
 * 순서를 관리한다(REQ-014, REQ-022).
 */
public interface MarketIndicatorSource {

    /**
     * 지정 날짜의 시장 지표를 수집한다 (일봉 배치용).
     *
     * @param date 수집 대상 날짜 (KST 기준)
     * @return 정규화된 행 목록. 빈 리스트이면 Fallback으로 이행한다.
     */
    List<MarketIndicatorRow> fetchDaily(LocalDate date);

    /**
     * 전체 이력을 단일 호출로 수집한다 (백필용).
     *
     * <p>KOREAEXIM처럼 전체 이력 단일 호출을 지원하지 않는 소스는 빈 리스트를 반환하여 백필 오케스트레이터가 날짜 루프를 사용하도록 유도한다(REQ-041).
     *
     * @return 전체 이력 행 목록
     */
    default List<MarketIndicatorRow> fetchHistory() {
        return List.of();
    }

    /** 소스 식별자 (로그용). */
    String sourceName();
}
