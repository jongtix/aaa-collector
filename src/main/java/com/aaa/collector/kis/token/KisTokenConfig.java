package com.aaa.collector.kis.token;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * KIS 토큰 모듈 빈 설정.
 *
 * <p>프로덕션 환경에서 사용할 {@link LockFactory} 기본 구현체를 등록한다. 테스트에서는 mock {@link
 * java.util.concurrent.locks.Lock}을 반환하는 구현체를 직접 주입하여 락 획득 실패 경로를 실제 대기 없이 검증할 수 있다.
 */
@Configuration
public class KisTokenConfig {

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
        var settings =
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT);

        return restClientBuilder
                .baseUrl(kisProperties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }

    /**
     * 계좌 alias마다 {@link ReentrantLock}을 생성하는 기본 {@link LockFactory} 빈.
     *
     * @return 프로덕션용 LockFactory 구현체
     */
    @Bean
    LockFactory lockFactory() {
        return key -> new ReentrantLock();
    }
}
