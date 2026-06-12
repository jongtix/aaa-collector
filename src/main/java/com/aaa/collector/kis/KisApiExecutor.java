package com.aaa.collector.kis;

import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import com.aaa.collector.kis.token.KisTokenService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/** KIS REST API 공통 실행기. 인증 헤더 조립, 응답 검증을 일괄 처리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisApiExecutor {

    private static final String EGW00201 = "EGW00201";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient kisRestClient;
    private final KisProperties kisProperties;
    private final KisTokenService kisTokenService;

    /**
     * KIS REST API GET 요청을 실행한다 (단일키 경로).
     *
     * <p>공통 인증 헤더 설정, 응답 바디 null 검증, {@link KisApiResponse#validateRtCd()} 호출을 일괄 처리한다.
     * isa(firstCredential) 단일키를 사용한다 — SPEC-COLLECTOR-TOKEN-001 제약 유지.
     *
     * @param uriCustomizer URI 빌더 커스터마이저 (path + query params)
     * @param trId KIS TR ID
     * @param responseType 응답 역직렬화 대상 클래스
     * @return 검증 완료된 응답 객체
     */
    // @MX:ANCHOR: [AUTO] 단일키(isa) 경로 진입점 — ①단계(관심종목 그룹/종목 목록)만 사용, ②단계는 WLSYNC-006으로 멀티키 전환됨
    // @MX:REASON: SPEC-COLLECTOR-WLSYNC-006 이후 ②단계(KisStockInfoClient)는 더 이상 단일키 경로를 쓰지 않음.
    //             메서드 자체는 ①단계(KisWatchlistClient) 단일키 호출이 계속 사용하므로 보존(SPEC-COLLECTOR-TOKEN-001 제약
    // 유지)
    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-006
    public <T extends KisApiResponse> T executeGet(
            Function<UriBuilder, URI> uriCustomizer, String trId, Class<T> responseType) {

        return executeGet(firstCredential(), uriCustomizer, trId, responseType);
    }

    /**
     * KIS REST API GET 요청을 실행한다 (멀티키 경로).
     *
     * <p>자격증명을 명시적으로 받아 해당 키의 access_token을 {@link
     * com.aaa.collector.kis.token.KisTokenService#getValidToken(String)} Lazy 발급으로 확보한다. HTTP 5xx
     * 응답 시 {@code msg_cd == "EGW00201"}이면 {@link KisRateLimitException}을 던진다.
     *
     * @param credential 호출에 사용할 계좌 자격증명
     * @param uriCustomizer URI 빌더 커스터마이저
     * @param trId KIS TR ID
     * @param responseType 응답 역직렬화 대상 클래스
     * @return 검증 완료된 응답 객체
     * @throws KisRateLimitException HTTP 500 + msg_cd=EGW00201 rate-limit 오류 식별 시
     */
    // @MX:ANCHOR: [AUTO] 멀티키 배치 경로 진입점 — EGW00201 식별·KisRateLimitException 던짐 포함
    // @MX:REASON: SPEC-COLLECTOR-BATCH-001 REQ-BATCH-001,-003 — 배치 호출의 5키 분산 실행기
    // @MX:SPEC: SPEC-COLLECTOR-BATCH-001
    public <T extends KisApiResponse> T executeGet(
            KisAccountCredential credential,
            Function<UriBuilder, URI> uriCustomizer,
            String trId,
            Class<T> responseType) {

        String token = kisTokenService.getValidToken(credential.alias());
        T response = fetchBodyMultikey(uriCustomizer, trId, credential, token, responseType);
        Objects.requireNonNull(response, "KIS API 응답 바디가 null입니다 — tr_id=" + trId);
        response.validateRtCd();
        return response;
    }

    /**
     * 멀티키 경로 전용 HTTP GET 실행. HTTP 5xx 응답을 onStatus 핸들러로 가로채어 EGW00201 여부를 판별한다.
     *
     * <p>msg_cd == EGW00201 → {@link KisRateLimitException} (일시적·재시도 가능). 그 외 5xx → 원래 {@link
     * RestClientResponseException} 그대로 전파 (영구 오류, 재시도 없음).
     */
    private <T extends KisApiResponse> T fetchBodyMultikey(
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
                .onStatus(
                        HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                            byte[] bodyBytes = response.getBody().readAllBytes();
                            String bodyText = new String(bodyBytes, StandardCharsets.UTF_8);
                            if (isEgw00201(bodyText)) {
                                throw new KisRateLimitException(
                                        credential.alias(),
                                        "EGW00201 rate-limit 오류 — tr_id=" + trId);
                            }
                            // 영구 오류 — RestClient 기본 예외로 재던지도록 body를 소비 후 전파
                            throw new RestClientResponseException(
                                    "KIS API 5xx 오류 — status="
                                            + response.getStatusCode()
                                            + ", body="
                                            + bodyText,
                                    response.getStatusCode(),
                                    response.getStatusText(),
                                    response.getHeaders(),
                                    bodyBytes,
                                    StandardCharsets.UTF_8);
                        })
                .body(responseType);
    }

    /**
     * HTTP 500 응답 바디에서 {@code msg_cd == "EGW00201"}인지 파싱한다.
     *
     * <p>JSON 파싱 실패 시 false를 반환하여 영구 오류 경로로 진행한다.
     */
    private static boolean isEgw00201(String bodyText) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(bodyText);
            JsonNode msgCd = node.get("msg_cd");
            return msgCd != null && EGW00201.equals(msgCd.asText());
        } catch (JsonProcessingException e) {
            // 파싱 실패 시 false 반환 → 영구 오류 경로로 진행 (잘못된 retry-vs-permanent 판별 방지)
            // bodyText는 KIS 서버 응답이므로 appkey/token을 포함하지 않음 — warn 수준 안전
            log.warn(
                    "EGW00201 판별 중 JSON 파싱 실패 — body={}, exceptionType={}",
                    bodyText,
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private KisAccountCredential firstCredential() {
        List<KisAccountCredential> accounts = kisProperties.accounts();
        if (accounts == null || accounts.isEmpty()) {
            throw new IllegalStateException("KIS 계좌 설정이 없습니다. 환경 변수를 확인하세요.");
        }
        return accounts.getFirst();
    }
}
