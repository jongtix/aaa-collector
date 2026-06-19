package com.aaa.collector.watchlist;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.KisProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * KIS 관심종목 API 클라이언트.
 *
 * <p>①단계 관심 그룹/종목 조회. 모든 호출은 단일 보호 게이트 {@link GuardedKisExecutor}를 경유한다 (SPEC-COLLECTOR-KISGATE-001
 * REQ-KISGATE-001). 키 lease·throttle·재시도(retryable: {@code KisRateLimitException} ∪ {@code
 * RestClientException})는 게이트가 단일 위치에서 수행하므로 본 클라이언트는 더 이상 {@code RetryExecutor}/{@code Sleeper}를 직접
 * 구성하지 않는다.
 *
 * <p><strong>throttle 경로 차이(DP2/REQ-KISGATE-030a/040):</strong>
 *
 * <ul>
 *   <li>{@link #fetchGroups()} — 1회성 그룹목록 조회. throttle-off로 게이트를 경유한다(키는 lease하되 rate limiter
 *       consume 생략, 기존 1-shot 동작 보존, REQ-KISGATE-040/AC-8). 표준 진입점이 아니므로 자체 단발 세션을 연다(per-batch 스냅샷
 *       1회, REQ-KISGATE-006a).
 *   <li>{@link #fetchStocksByGroup(LeaseSession, String)} — 그룹별 종목 조회. throttle-on으로 게이트를 경유해 <b>매
 *       시도마다 rate limiter를 새로 경유</b>한다(REQ-KISGATE-030/030a — 기존 "외부 슬롯 1개를 retry 전체 점유"에서 바뀐 의도된
 *       동작 변경). 배치 오케스트레이터({@link WatchlistSyncService})가 작업 단위당 1회 연 {@link LeaseSession}을
 *       주입받는다(per-batch 스냅샷 공유, REQ-KISGATE-006a/031).
 * </ul>
 *
 * <p>재시도 소진 시 게이트가 마지막 retryable 예외를 전파하며, 상위 호출부({@link WatchlistSyncService})가 그룹 skip 정책을
 * 적용한다(REQ-KISGATE-022/AC-7).
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

    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final KisProperties kisProperties;

    /**
     * 관심 그룹 목록을 조회한다(1회성, throttle-off).
     *
     * <p>게이트를 throttle-off(REQ-KISGATE-040)로 경유한다 — 키만 lease하고 rate limiter consume은 생략하여 기존 1-shot
     * 동작을 보존한다(AC-8). 단발 호출이므로 자체 {@link LeaseSession}을 1회 열어 사용한다(per-batch 스냅샷,
     * REQ-KISGATE-006a). 소진 시 게이트가 예외를 전파한다(호출부가 그룹 skip 처리).
     */
    public List<KisGroupListResponse.Group> fetchGroups() {
        LeaseSession session = keyLeaseRegistry.openSession();
        try {
            KisGroupListResponse response =
                    guardedKisExecutor.execute(
                            session,
                            uriBuilder ->
                                    uriBuilder
                                            .path(PATH_GROUP_LIST)
                                            .queryParam("TYPE", "1")
                                            .queryParam("FID_ETC_CLS_CODE", "00")
                                            .queryParam("USER_ID", kisProperties.userId())
                                            .build(),
                            TR_ID_GROUP_LIST,
                            KisGroupListResponse.class,
                            false);
            return response.output2() != null ? response.output2() : List.of();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fetchGroups 중 인터럽트", ie);
        }
    }

    /**
     * 그룹별 종목 목록을 조회한다(throttle-on, 매 시도 rate limiter 재경유).
     *
     * <p>게이트를 기본(throttle-on) 경로로 경유한다 — 재시도 루프 내에서 매 시도마다 키를 lease하고 rate limiter를 새로
     * 경유한다(REQ-KISGATE-030/030a). 기존 "{@code WatchlistSyncService} 외부 슬롯 1개를 retry 3회 내내 점유"에서 바뀐
     * 의도된 동작 변경이다(Behavior:Changed). 소진 시 게이트가 예외를 전파한다(호출부가 그룹 skip 처리, REQ-KISGATE-022).
     *
     * @param session 배치 오케스트레이터가 작업 단위당 1회 연 per-batch 헬스 스냅샷 세션(REQ-KISGATE-006a)
     * @param groupCode {@link KisGroupListResponse.Group#interGrpCode()} 값
     */
    public List<KisStockListByGroupResponse.Stock> fetchStocksByGroup(
            LeaseSession session, String groupCode) {
        try {
            KisStockListByGroupResponse response =
                    guardedKisExecutor.execute(
                            session,
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
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fetchStocksByGroup 중 인터럽트", ie);
        }
    }
}
