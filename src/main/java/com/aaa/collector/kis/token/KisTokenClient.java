package com.aaa.collector.kis.token;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * KIS Open API 토큰 발급 클라이언트.
 *
 * <p>{@code POST /oauth2/tokenP} 엔드포인트를 호출하여 액세스 토큰을 발급받는다. 에러 핸들링은 호출 측에서 처리한다.
 */
@Component
@RequiredArgsConstructor
public class KisTokenClient {

    private final RestClient kisRestClient;

    /**
     * 계좌 자격증명으로 KIS API 액세스 토큰을 발급받는다.
     *
     * @param credential 앱키와 앱시크릿을 포함한 계좌 자격증명
     * @return KIS API 토큰 응답
     */
    public KisTokenResponse requestToken(KisAccountCredential credential) {
        return kisRestClient
                .post()
                .uri("/oauth2/tokenP")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        Map.of(
                                "grant_type", "client_credentials",
                                "appkey", credential.appKey(),
                                "appsecret", credential.appSecret()))
                .retrieve()
                .body(KisTokenResponse.class);
    }
}
