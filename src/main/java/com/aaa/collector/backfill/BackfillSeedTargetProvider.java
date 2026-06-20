package com.aaa.collector.backfill;

import java.util.List;

/**
 * cron 진입부 lazy 시딩 대상(활성 관심종목)을 시장별로 제공하는 포트 (SPEC-COLLECTOR-BACKFILL-001 T2).
 *
 * <p>{@link BackfillStatusSeeder}가 시딩 대상 종목 심볼을 시장별로 얻기 위한 의존성 역전 포트다. 구현 어댑터({@code stock} 패키지)는
 * {@code StockRepository}/{@code Stock}/{@code Market}을 사용해 활성 종목을 시장별로 분류하지만, 이 포트는 {@code stock}
 * 타입(엔티티·enum)을 노출하지 않고 심볼 문자열만 반환한다.
 *
 * <p>이 포트가 없으면 {@code backfill} 패키지가 {@code stock} 패키지를 직접 의존(StockRepository/Stock/Market)하게 되어
 * {@code stock → backfill}(T3 수집 서비스가 {@link BackfillWindowResult}를 반환) 의존과 결합해 피처 패키지 순환 의존을
 * 형성한다(MdcArchitectureTest {@code noCircularDependenciesBetweenFeaturePackages} 위반). 시딩 대상 분류를 이
 * 포트로 역전해 {@code backfill → stock} 방향 의존을 제거한다.
 */
public interface BackfillSeedTargetProvider {

    /**
     * 4개 data_table(국내 일봉·투자자동향·공매도·신용잔고)을 시딩할 활성 국내 종목 심볼.
     *
     * @return 활성 국내(KOSPI/KOSDAQ) 종목 심볼 목록
     */
    List<String> activeDomesticSymbols();

    /**
     * {@code daily_ohlcv} 1행만 시딩할 활성 미국 종목 심볼(수급 3종 비대상, AC-7.3).
     *
     * @return 활성 미국(NYSE/NASDAQ/AMEX) 종목 심볼 목록
     */
    List<String> activeOverseasSymbols();
}
