package com.aaa.collector.watchlist;

import com.aaa.collector.common.retry.RetryExecutor;
import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.token.KisProperties;
import java.util.List;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * KIS 관심종목 API 클라이언트.
 *
 * <p>①단계 관심 그룹/종목 조회. {@link RetryExecutor}로 {@code RestClientException} 재시도(maxAttempts=3,
 * baseDelay=500ms)와 {@code KisRateLimitException}(EGW00201) 재시도(D2 신규 동작)를 수행한다.
 *
 * <p>소진 시 예외를 전파하여 상위 호출부({@link WatchlistSyncService})가 그룹 skip 정책을 적용한다.
 */
@Component
@RequiredArgsConstructor
public class KisWatchlistClient {

    private static final String TR_ID_GROUP_LIST = "HHKCM113004C7";
    private static final String TR_ID_STOCK_LIST_BY_GROUP = "HHKCM113004C6";
    private static final String PATH_GROUP_LIST =
            "/uapi/domestic-stock/v1/quotations/intstock-grouplist";
    private static final String PATH_STOCK_LIST_BY_GROUP =
            "/uapi/domestic-stock/v1/quotations/intstock-stocklist-by-group";

    /** ①단계 maxAttempts (기존 Spring Retry maxAttempts=3 보존). */
    private static final int MAX_ATTEMPTS = 3;

    /** ①단계 baseDelay (기존 @Backoff delay=500ms 보존, AC-9.1). */
    private static final long BASE_DELAY_MS = 500L;

    /**
     * ①단계 재시도 정책: KisRateLimitException(EGW00201) 및 RestClientException(네트워크 오류) retryable. 그 외 예외는
     * permanent — 즉시 전파.
     */
    private static final Predicate<RuntimeException> RETRYABLE =
            ex -> ex instanceof KisRateLimitException || ex instanceof RestClientException;

    private final KisApiExecutor kisApiExecutor;
    private final KisProperties kisProperties;
    private final Sleeper sleeper;

    /**
     * 관심 그룹 목록을 조회한다.
     *
     * <p>{@code RestClientException} 및 {@code KisRateLimitException}(EGW00201) 발생 시 {@link
     * RetryExecutor}로 재시도한다. 소진 시 예외를 전파한다(호출부가 그룹 skip 처리).
     */
    public List<KisGroupListResponse.Group> fetchGroups() {
        RetryExecutor retryExecutor =
                new RetryExecutor(MAX_ATTEMPTS, BASE_DELAY_MS, sleeper, RETRYABLE);
        try {
            return retryExecutor.execute(
                    () -> {
                        KisGroupListResponse response =
                                kisApiExecutor.executeGet(
                                        uriBuilder ->
                                                uriBuilder
                                                        .path(PATH_GROUP_LIST)
                                                        .queryParam("TYPE", "1")
                                                        .queryParam("FID_ETC_CLS_CODE", "00")
                                                        .queryParam(
                                                                "USER_ID", kisProperties.userId())
                                                        .build(),
                                        TR_ID_GROUP_LIST,
                                        KisGroupListResponse.class);
                        return response.output2() != null ? response.output2() : List.of();
                    });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fetchGroups 중 인터럽트", ie);
        }
    }

    /**
     * 그룹별 종목 목록을 조회한다.
     *
     * <p>{@code RestClientException} 및 {@code KisRateLimitException}(EGW00201) 발생 시 {@link
     * RetryExecutor}로 재시도한다. 소진 시 예외를 전파한다(호출부가 그룹 skip 처리).
     *
     * @param groupCode {@link KisGroupListResponse.Group#interGrpCode()} 값
     */
    public List<KisStockListByGroupResponse.Stock> fetchStocksByGroup(String groupCode) {
        RetryExecutor retryExecutor =
                new RetryExecutor(MAX_ATTEMPTS, BASE_DELAY_MS, sleeper, RETRYABLE);
        try {
            return retryExecutor.execute(
                    () -> {
                        KisStockListByGroupResponse response =
                                kisApiExecutor.executeGet(
                                        uriBuilder ->
                                                uriBuilder
                                                        .path(PATH_STOCK_LIST_BY_GROUP)
                                                        .queryParam("TYPE", "1")
                                                        .queryParam(
                                                                "USER_ID", kisProperties.userId())
                                                        .queryParam("INTER_GRP_CODE", groupCode)
                                                        .queryParam("DATA_RANK", "")
                                                        .queryParam("INTER_GRP_NAME", "")
                                                        .queryParam("HTS_KOR_ISNM", "")
                                                        .queryParam("CNTG_CLS_CODE", "")
                                                        .queryParam("FID_ETC_CLS_CODE", "4")
                                                        .build(),
                                        TR_ID_STOCK_LIST_BY_GROUP,
                                        KisStockListByGroupResponse.class);
                        return response.output2() != null ? response.output2() : List.of();
                    });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fetchStocksByGroup 중 인터럽트", ie);
        }
    }
}
