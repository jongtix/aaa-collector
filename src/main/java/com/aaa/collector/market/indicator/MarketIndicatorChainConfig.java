package com.aaa.collector.market.indicator;

import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.usdkrw.KoreaeximExchangeRateClient;
import com.aaa.collector.market.indicator.vix.CboeVixClient;
import com.aaa.collector.market.session.MarketSessionGate;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시장 지표 소스 체인 빈 설정 (SPEC-COLLECTOR-MARKETIND-001; SPEC-COLLECTOR-MARKETIND-003 REQ-030 FRED 제거).
 *
 * <p>VIX 체인: [CBOE, Yahoo^VIX]. USDKRW 체인: [KOREAEXIM, Yahoo USDKRW=X]. {@link YahooFinanceClient}는
 * 지표 코드별 어댑터({@link MarketIndicatorSource})로 래핑하여 체인에 등록한다.
 */
@Configuration
public class MarketIndicatorChainConfig {

    /** VIX 전용 Yahoo Fallback 소스 (^VIX 심볼). */
    @Bean
    MarketIndicatorSource yahooVixSource(YahooFinanceClient yahooFinanceClient) {
        return new MarketIndicatorSource() {
            @Override
            public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
                return yahooFinanceClient.fetchDaily(IndicatorCode.VIX, date);
            }

            @Override
            public List<MarketIndicatorRow> fetchHistory() {
                return yahooFinanceClient.fetchHistory(IndicatorCode.VIX);
            }

            @Override
            public List<MarketIndicatorRow> fetchRange(LocalDate from, LocalDate to) {
                return yahooFinanceClient.fetchRange(IndicatorCode.VIX, from, to);
            }

            @Override
            public String sourceName() {
                return "YAHOO_VIX";
            }
        };
    }

    /** USDKRW 전용 Yahoo Fallback 소스 (USDKRW=X 심볼). */
    @Bean
    MarketIndicatorSource yahooUsdkrwSource(YahooFinanceClient yahooFinanceClient) {
        return new MarketIndicatorSource() {
            @Override
            public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
                return yahooFinanceClient.fetchDaily(IndicatorCode.USDKRW, date);
            }

            @Override
            public List<MarketIndicatorRow> fetchHistory() {
                return yahooFinanceClient.fetchHistory(IndicatorCode.USDKRW);
            }

            @Override
            public String sourceName() {
                return "YAHOO_USDKRW";
            }
        };
    }

    /** VIX 소스 체인: CBOE → Yahoo^VIX (SPEC-COLLECTOR-MARKETIND-003 REQ-030 — FRED 제거). */
    @Bean
    @Qualifier("vixChain") MarketIndicatorSourceChain vixChain(
            CboeVixClient cboeVixClient,
            @Qualifier("yahooVixSource") MarketIndicatorSource yahooVixSource,
            MarketIndicatorMetrics metrics) {
        return new MarketIndicatorSourceChain(
                List.of(cboeVixClient, yahooVixSource), "VIX", metrics);
    }

    /**
     * USDKRW 소스 체인: KOREAEXIM → Yahoo USDKRW=X.
     *
     * <p>{@link MarketSessionGate}(기존 {@code @Component}, {@code MarketOpenGate} 구현체)를 주입받아 "대상 날짜가
     * KRX 휴장일인 경우"를 primary 예상-빈 조건으로 배선한다(SPEC-COLLECTOR-MARKETIND-006 REQ-013). {@code
     * isOpenDay}는 fail-open(캘린더 미로드/날짜 미존재 시 개장으로 판정)이므로, 조건이 참이 되는 것은 "확실히 휴장일로 아는" 경우로 한정되어 불확실
     * 상황이 오탐 억제 방향으로 넘어가지 않는다(REQ-015).
     */
    // @MX:NOTE: [AUTO] MarketSessionGate.isOpenDay의 부정을 predicate로 배선 — fail-open이라 알람 누락
    // 방향으로 미전이
    // @MX:SPEC: SPEC-COLLECTOR-MARKETIND-006 REQ-013, REQ-015
    @Bean
    @Qualifier("usdkrwChain") MarketIndicatorSourceChain usdkrwChain(
            KoreaeximExchangeRateClient koreaeximClient,
            @Qualifier("yahooUsdkrwSource") MarketIndicatorSource yahooUsdkrwSource,
            MarketIndicatorMetrics metrics,
            MarketSessionGate marketSessionGate) {
        return new MarketIndicatorSourceChain(
                List.of(koreaeximClient, yahooUsdkrwSource),
                "USDKRW",
                metrics,
                date -> !marketSessionGate.isOpenDay(date));
    }

    /**
     * RestClient 빈 리졸버: cboeRestClient 이름으로 등록된 빈 주입.
     *
     * <p>CboeVixClient는 {@code cboeRestClient} 빈을 직접 주입받으므로 Config 의존 없음.
     */
}
