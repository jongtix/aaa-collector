package com.aaa.collector.stock.exthours;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Yahoo Finance 시간외 가격 수집 전용 인프라 빈 설정 (SPEC-COLLECTOR-EXTHOURS-001).
 *
 * <p>Yahoo Finance는 인증이 불필요한 비공식 공개 API이므로 KIS 게이트({@code GuardedKisExecutor}/{@code
 * KisApiExecutor})를 경유하지 않고 별도 {@link RestClient}로 호출한다(REQ-EXTH-005, REQ-EXTH-010).
 */
@Configuration
public class ExtendedHoursClientConfig {

    static final String YAHOO_EXTENDED_HOURS_BASE_URL = "https://query2.finance.yahoo.com";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    /**
     * Yahoo Finance 시간외 수집 전용 {@link RestClient} 빈.
     *
     * <p>기존 {@code yahooRestClient} 빈({@code YahooFinanceClient} 의존)과 충돌하지 않도록 별도 빈 이름으로
     * 등록한다(REQ-EXTH-010).
     *
     * @param builder Spring 자동 구성 빌더(메시지 컨버터·ObjectMapper 상속)
     * @return Yahoo Finance 시간외 호출용 RestClient
     */
    @Bean
    RestClient yahooExtendedHoursRestClient(RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT);
        return builder.baseUrl(YAHOO_EXTENDED_HOURS_BASE_URL)
                .defaultHeader("User-Agent", USER_AGENT)
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }

    /**
     * 프로덕션 {@link ExtendedHoursSleeper} 빈 — {@code Thread.sleep(ms)} 호출 (REQ-EXTH-011).
     *
     * <p>테스트에서는 {@link ExtendedHoursSleeper#noOp()} 또는 mock으로 교체한다.
     *
     * @return Thread.sleep 기반 Sleeper
     */
    @Bean
    ExtendedHoursSleeper extendedHoursSleeper() {
        return Thread::sleep;
    }
}
