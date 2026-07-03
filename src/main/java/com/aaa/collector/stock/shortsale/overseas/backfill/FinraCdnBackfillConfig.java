package com.aaa.collector.stock.shortsale.overseas.backfill;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * FINRA CDN 정적 파일 전용 인프라 빈 설정 (SPEC-COLLECTOR-BACKFILL-008 T2).
 *
 * <p>FINRA CDN은 인증이 불필요한 무인증 정적 파일 서버이므로 KIS 게이트({@code GuardedKisExecutor}/{@code
 * KisApiExecutor})를 경유하지 않고 별도 {@link RestClient}로 호출한다(REQ-BACKFILL-100a). 라이브 {@code
 * FinraClientConfig}(REST Query API 전용, {@code https://api.finra.org})와는 독립된 별도 빈이다 — 베이스 URL이
 * 다르고(CDN vs REST API) 라이브 경로를 변경하지 않는다.
 */
// @MX:NOTE: [AUTO] 무인증 CDN 전용 빈 설정 — KIS 게이트 우회, baseUrl/timeout을 라이브 FinraClientConfig와 분리
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-008
@Configuration
@RequiredArgsConstructor
public class FinraCdnBackfillConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final FinraCdnShortSaleBackfillProperties properties;

    /**
     * FINRA CDN 정적 파일 전용 {@link RestClient} 빈.
     *
     * @param restClientBuilder Spring 자동 구성 빌더
     * @return FINRA CDN 호출용 RestClient({@code Authorization} 헤더 없음)
     */
    @Bean
    RestClient finraCdnRestClient(RestClient.Builder restClientBuilder) {
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT);

        return restClientBuilder
                .baseUrl(properties.getCdnBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }
}
