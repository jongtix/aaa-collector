package com.aaa.collector.macro.ecos;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 한국은행 ECOS API 전용 인프라 빈 설정 (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-002).
 *
 * <p>ECOS는 인증 없이 serviceKey를 URL 경로 세그먼트에 포함하는 공개 REST API이므로 KIS 게이트를 경유하지 않고 별도 {@link
 * RestClient}로 호출한다.
 */
@Configuration
public class EcosClientConfig {

    static final String ECOS_BASE_URL = "https://ecos.bok.or.kr";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /**
     * ECOS API 전용 {@link RestClient} 빈.
     *
     * @param restClientBuilder Spring 자동 구성 빌더(메시지 컨버터·ObjectMapper 상속)
     * @return ECOS 호출용 RestClient
     */
    @Bean
    RestClient ecosRestClient(RestClient.Builder restClientBuilder) {
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT);

        return restClientBuilder
                .baseUrl(ECOS_BASE_URL)
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }
}
