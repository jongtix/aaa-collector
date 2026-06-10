package com.aaa.collector.kis;

import com.aaa.collector.kis.token.KisProperties;
import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** KIS API 공통 인프라 빈 설정. */
@Configuration
public class KisConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * KIS API 전용 {@link RestClient} 빈.
     *
     * <p>{@link KisProperties#baseUrl()}을 기반으로 연결/읽기 타임아웃이 설정된 RestClient를 생성한다.
     *
     * @return KIS API 호출용 RestClient
     */
    @Bean
    RestClient kisRestClient(RestClient.Builder restClientBuilder, KisProperties kisProperties) {
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT);

        return restClientBuilder
                .baseUrl(kisProperties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }

    /**
     * KIS API rate limiter 빈.
     *
     * <p>기존 단일키 경로({@code WatchlistSyncService} 등)가 사용하는 단일 빈. SPEC-COLLECTOR-WLSYNC-005 유지.
     *
     * @return {@link KisProperties#rateLimit()} 설정 기반 KisRateLimiter
     */
    @Bean
    KisRateLimiter kisRateLimiter(KisProperties kisProperties) {
        return new KisRateLimiter(kisProperties.rateLimit());
    }

    /**
     * 앱키별 독립 {@link KisRateLimiter} 인스턴스 레지스트리 빈.
     *
     * <p>배치 멀티키 경로에서 alias별 rate limiter를 획득하는 데 사용한다. 기존 단일 빈({@link #kisRateLimiter})은 유지한다.
     *
     * @return alias → KisRateLimiter 레지스트리
     */
    @Bean
    KisRateLimiterRegistry kisRateLimiterRegistry(KisProperties kisProperties) {
        return new KisRateLimiterRegistry(kisProperties);
    }
}
