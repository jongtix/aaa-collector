package com.aaa.collector.stock.shortsale.overseas;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * FINRA REST Query API 전용 인프라 빈 설정.
 *
 * <p>FINRA는 인증이 불필요한 공개 REST API(REQ-SSO-005)이므로 KIS 게이트({@code GuardedKisExecutor}/{@code
 * KisApiExecutor})를 경유하지 않고 별도 {@link RestClient}로 호출한다(REQ-SSO-006). KIS 전용 ArchUnit
 * 가드(`KisApiExecutorGateGuardTest`)는 {@code KisApiExecutor.executeGet} 직접 호출만 가드하므로 본 빈과 충돌하지 않는다.
 */
@Configuration
public class FinraClientConfig {

    static final String FINRA_BASE_URL = "https://api.finra.org";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /**
     * FINRA REST Query API 전용 {@link RestClient} 빈.
     *
     * @param restClientBuilder Spring 자동 구성 빌더(메시지 컨버터·ObjectMapper 상속)
     * @return FINRA 호출용 RestClient
     */
    @Bean
    RestClient finraRestClient(RestClient.Builder restClientBuilder) {
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT);

        return restClientBuilder
                .baseUrl(FINRA_BASE_URL)
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }
}
