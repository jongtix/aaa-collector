package com.aaa.collector.dart.external;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * OpenDART API 전용 HTTP 클라이언트 빈 설정 (SPEC-COLLECTOR-DART-001).
 *
 * <p>KIS 게이트를 경유하지 않는 독립 빈이다. {@link MarketIndicatorClientConfig} 패턴 준용.
 */
@Configuration
public class DartClientConfig {

    static final String DART_BASE_URL = "https://opendart.fss.or.kr";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /**
     * OpenDART API 전용 {@link RestClient} 빈.
     *
     * @param builder Spring 자동 구성 빌더(메시지 컨버터·ObjectMapper 상속)
     * @return DART 호출용 RestClient
     */
    @Bean
    RestClient dartRestClient(RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT);
        return builder.baseUrl(DART_BASE_URL)
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }
}
