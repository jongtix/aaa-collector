package com.aaa.collector.kis.token;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * KIS Open API 토큰/승인키 발급 클라이언트.
 *
 * <p>{@code POST /oauth2/tokenP} 및 {@code POST /oauth2/Approval} 엔드포인트를 호출한다. 에러 핸들링은 호출 측에서 처리한다.
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

    /**
     * 계좌 자격증명으로 KIS WebSocket 접속용 승인키를 발급받는다.
     *
     * <p>{@code POST /oauth2/Approval} 엔드포인트를 호출한다. 요청 파라미터 중 시크릿 필드명이 토큰 발급({@code appsecret})과 달리
     * {@code secretkey}임에 주의한다.
     *
     * @param credential 앱키와 앱시크릿을 포함한 계좌 자격증명
     * @return KIS API 승인키 응답
     */
    public KisApprovalKeyResponse requestApprovalKey(KisAccountCredential credential) {
        return kisRestClient
                .post()
                .uri("/oauth2/Approval")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        Map.of(
                                "grant_type", "client_credentials",
                                "appkey", credential.appKey(),
                                "secretkey", credential.appSecret()))
                .retrieve()
                .body(KisApprovalKeyResponse.class);
    }
}
