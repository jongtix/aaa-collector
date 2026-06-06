package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.token.KisProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/** KIS 관심종목 API 클라이언트. */
@Component
@RequiredArgsConstructor
public class KisWatchlistClient {

    private static final String TR_ID_GROUP_LIST = "HHKCM113004C7";
    private static final String TR_ID_STOCK_LIST_BY_GROUP = "HHKCM113004C6";
    private static final String PATH_GROUP_LIST =
            "/uapi/domestic-stock/v1/quotations/intstock-grouplist";
    private static final String PATH_STOCK_LIST_BY_GROUP =
            "/uapi/domestic-stock/v1/quotations/intstock-stocklist-by-group";

    private final KisApiExecutor kisApiExecutor;
    private final KisProperties kisProperties;

    /** 관심 그룹 목록을 조회한다. */
    @Retryable(
            retryFor = {RestClientException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2))
    public List<KisGroupListResponse.Group> fetchGroups() {
        KisGroupListResponse response =
                kisApiExecutor.executeGet(
                        uriBuilder ->
                                uriBuilder
                                        .path(PATH_GROUP_LIST)
                                        .queryParam("TYPE", "1")
                                        .queryParam("FID_ETC_CLS_CODE", "00")
                                        .queryParam("USER_ID", kisProperties.userId())
                                        .build(),
                        TR_ID_GROUP_LIST,
                        KisGroupListResponse.class);

        return response.output2() != null ? response.output2() : List.of();
    }

    /**
     * 그룹별 종목 목록을 조회한다.
     *
     * @param groupCode {@link KisGroupListResponse.Group#interGrpCode()} 값
     */
    @Retryable(
            retryFor = {RestClientException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2))
    public List<KisStockListByGroupResponse.Stock> fetchStocksByGroup(String groupCode) {
        KisStockListByGroupResponse response =
                kisApiExecutor.executeGet(
                        uriBuilder ->
                                uriBuilder
                                        .path(PATH_STOCK_LIST_BY_GROUP)
                                        .queryParam("TYPE", "1")
                                        .queryParam("USER_ID", kisProperties.userId())
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
    }
}
