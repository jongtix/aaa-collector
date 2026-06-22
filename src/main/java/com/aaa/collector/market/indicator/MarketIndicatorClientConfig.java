package com.aaa.collector.market.indicator;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 시장 지표 외부 소스 전용 HTTP 클라이언트 빈 설정 (REQ-063).
 *
 * <p>KIS 게이트({@code GuardedKisExecutor}/{@code KisApiExecutor})를 경유하지 않는 독립 빈이다. CBOE / FRED /
 * Yahoo / KOREAEXIM 각각 별도 빈으로 구성한다.
 */
@Configuration
public class MarketIndicatorClientConfig {

    static final String CBOE_BASE_URL = "https://cdn.cboe.com";
    static final String FRED_BASE_URL = "https://api.stlouisfed.org";
    static final String YAHOO_BASE_URL = "https://query1.finance.yahoo.com";
    static final String KOREAEXIM_BASE_URL = "https://oapi.koreaexim.go.kr";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private RestClient build(RestClient.Builder builder, String baseUrl) {
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT);
        return builder.baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }

    /** CBOE VIX CSV 전용 RestClient. */
    @Bean
    RestClient cboeRestClient(RestClient.Builder builder) {
        return build(builder, CBOE_BASE_URL);
    }

    /** FRED API 전용 RestClient. */
    @Bean
    RestClient fredRestClient(RestClient.Builder builder) {
        return build(builder, FRED_BASE_URL);
    }

    /** Yahoo Finance v8 전용 RestClient. */
    @Bean
    RestClient yahooRestClient(RestClient.Builder builder) {
        return build(builder, YAHOO_BASE_URL);
    }

    /** 한국수출입은행 환율 API 전용 RestClient. */
    @Bean
    RestClient koreaeximRestClient(RestClient.Builder builder) {
        return build(builder, KOREAEXIM_BASE_URL);
    }
}
