package com.aaa.collector.kis;

import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import com.aaa.collector.kis.token.KisTokenService;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/** KIS REST API 공통 실행기. 인증 헤더 조립, 응답 검증을 일괄 처리한다. */
@Component
@RequiredArgsConstructor
public class KisApiExecutor {

    private final RestClient kisRestClient;
    private final KisProperties kisProperties;
    private final KisTokenService kisTokenService;

    /**
     * KIS REST API GET 요청을 실행한다.
     *
     * <p>공통 인증 헤더 설정, 응답 바디 null 검증, {@link KisApiResponse#validateRtCd()} 호출을 일괄 처리한다.
     *
     * @param uriCustomizer URI 빌더 커스터마이저 (path + query params)
     * @param trId KIS TR ID
     * @param responseType 응답 역직렬화 대상 클래스
     * @return 검증 완료된 응답 객체
     */
    public <T extends KisApiResponse> T executeGet(
            Function<UriBuilder, URI> uriCustomizer, String trId, Class<T> responseType) {

        KisAccountCredential credential = firstCredential();
        String token = kisTokenService.getValidToken(credential.alias());
        T response = fetchBody(uriCustomizer, trId, credential, token, responseType);
        Objects.requireNonNull(response, "KIS API 응답 바디가 null입니다 — tr_id=" + trId);
        response.validateRtCd();
        return response;
    }

    private <T extends KisApiResponse> T fetchBody(
            Function<UriBuilder, URI> uriCustomizer,
            String trId,
            KisAccountCredential credential,
            String token,
            Class<T> responseType) {
        return kisRestClient
                .get()
                .uri(uriCustomizer)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + token)
                .header("appkey", credential.appKey())
                .header("appsecret", credential.appSecret())
                .header("tr_id", trId)
                .header("custtype", "P")
                .retrieve()
                .body(responseType);
    }

    private KisAccountCredential firstCredential() {
        List<KisAccountCredential> accounts = kisProperties.accounts();
        if (accounts == null || accounts.isEmpty()) {
            throw new IllegalStateException("KIS 계좌 설정이 없습니다. 환경 변수를 확인하세요.");
        }
        return accounts.getFirst();
    }
}
