package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.KisRateLimiter;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.stock.enums.Market;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SPEC-COLLECTOR-KISGATE-001 M5(T14) — {@link WatchlistSyncService} 게이트 전환 단위 테스트.
 *
 * <p>Behavior:Changed(DP2)이므로 <b>회귀</b>(그룹 skip + failedGroupCount + markRemoved defer + classify
 * 항상 호출)와 <b>신규 동작</b>(외부 {@code kisRateLimiter} consume/release 제거 = 이중 throttle 방지 / 종목 조회 단계
 * {@code selectHealthy()} per-batch 1회 스냅샷)을 함께 검증한다.
 *
 * <p>{@code selectHealthy()} 호출 횟수 검증을 위해 실제 {@link KeyLeaseRegistry} + mock {@link
 * HealthyKeySelector}를 주입한다(게이트 자체는 client mock 뒤에 가려져 본 테스트 범위 밖).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WatchlistSyncService — 외부 throttle 제거 + per-batch 스냅샷")
class WatchlistSyncServiceTest {

    private static final KisAccountCredential K1 =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential K2 =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");

    @Mock private HealthyKeySelector healthyKeySelector;
    @Mock private KisWatchlistClient kisWatchlistClient;
    @Mock private WatchlistWriter watchlistWriter;
    @Mock private WatchlistStockResolver watchlistStockResolver;

    private WatchlistSyncService syncService;

    @BeforeEach
    void setUp() {
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        syncService =
                new WatchlistSyncService(
                        kisWatchlistClient,
                        keyLeaseRegistry,
                        watchlistWriter,
                        watchlistStockResolver);

        // 기본: 건강 키 2개 스냅샷. selectHealthy 호출 횟수는 개별 테스트가 검증.
        lenient().when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
        // ②단계는 resolver 위임 — 기본적으로 입력 raw를 그대로 통과시키는 stub(필요 시 개별 override).
        lenient()
                .when(watchlistStockResolver.resolve(anyList()))
                .thenAnswer(
                        inv -> {
                            List<KisStockListByGroupResponse.Stock> raw = inv.getArgument(0);
                            return raw.stream()
                                    .map(
                                            s ->
                                                    new ResolvedStock(
                                                            s.jongCode(),
                                                            s.htsKorIsnm(),
                                                            Market.KOSPI,
                                                            new StockInfo(null, null, null)))
                                    .toList();
                        });
    }

    @Nested
    @DisplayName("그룹 순회 + 위임 (회귀)")
    class GroupsAndDelegation {

        @Test
        @DisplayName("빈 그룹 목록 — fetchStocksByGroup·selectHealthy 미호출, upsertAll 빈 목록")
        void sync_emptyGroups_noFetchAndNoSnapshot() {
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of());

            syncService.sync();

            verify(kisWatchlistClient, never()).fetchStocksByGroup(any(), any());
            verify(healthyKeySelector, never()).selectHealthy();
            verify(watchlistWriter).upsertAll(eq(List.of()), eq(0));
        }

        @Test
        @DisplayName("여러 그룹 — 각 그룹별 fetchStocksByGroup(session, code) 호출")
        void sync_multipleGroups_fetchesStocksForEachGroup() {
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group1, group2));
            when(kisWatchlistClient.fetchStocksByGroup(any(), any())).thenReturn(List.of());

            syncService.sync();

            verify(kisWatchlistClient, times(1)).fetchStocksByGroup(any(), eq("001"));
            verify(kisWatchlistClient, times(1)).fetchStocksByGroup(any(), eq("002"));
        }

        @Test
        @DisplayName("동일 종목코드 다른 거래소 — dedup 키 다르므로 resolver에 2개 전달")
        void sync_sameSymbolDifferentExchange_treatedAsSeparateEntries() {
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock nas =
                    new KisStockListByGroupResponse.Stock("FS", "ACME", "NAS", "ACME Inc");
            KisStockListByGroupResponse.Stock nys =
                    new KisStockListByGroupResponse.Stock("FS", "ACME", "NYS", "ACME Inc");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup(any(), eq("001")))
                    .thenReturn(List.of(nas, nys));

            syncService.sync();

            verify(watchlistStockResolver).resolve(argThat(list -> list.size() == 2));
        }
    }

    @Nested
    @DisplayName("그룹 skip + failedGroupCount + markRemoved defer (회귀, AC-7)")
    class GroupSkipPreserved {

        @Test
        @DisplayName("fetchStocksByGroup 소진 전파 — 그룹 skip, failedGroupCount 증가, 나머지 그룹 정상")
        void sync_fetchStocksByGroupExhausted_skipsGroupAndIncrementsFailedCount() {
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("J", "005930", "KRX", "삼성전자");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group1, group2));
            // 001은 게이트 재시도 소진 예외 전파, 002는 정상
            when(kisWatchlistClient.fetchStocksByGroup(any(), eq("001")))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201 소진"));
            when(kisWatchlistClient.fetchStocksByGroup(any(), eq("002"))).thenReturn(List.of(item));

            syncService.sync();

            // 001 실패해도 002 종목 1개는 resolver→upsertAll, failedGroupCount=1 (markRemoved defer 트리거)
            verify(watchlistStockResolver).resolve(argThat(list -> list.size() == 1));
            ArgumentCaptor<List<ResolvedStock>> captor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(captor.capture(), eq(1));
            assertThat(captor.getValue()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("외부 throttle 제거 = 이중 throttle 방지 (신규 동작, DP2/R3)")
    class ExternalThrottleRemoved {

        @Test
        @DisplayName("서비스는 KisRateLimiter를 협력자로 갖지 않는다 (외부 consume/release 제거)")
        void syncService_hasNoGlobalRateLimiterCollaborator() {
            List<Class<?>> ctorParamTypes =
                    Arrays.asList(
                            WatchlistSyncService.class.getDeclaredConstructors()[0]
                                    .getParameterTypes());

            // 전역 KisRateLimiter 의존 제거 — throttle은 게이트(매 시도 per-key)가 담당, 외부 점유 없음.
            assertThat(ctorParamTypes).doesNotContain(KisRateLimiter.class);
            // 키 lease는 KeyLeaseRegistry로 per-batch 1회 스냅샷.
            assertThat(ctorParamTypes).contains(KeyLeaseRegistry.class, KisWatchlistClient.class);
        }
    }

    @Nested
    @DisplayName("per-batch 헬스 스냅샷 1회 (신규 동작, REQ-KISGATE-006a)")
    class PerBatchSnapshot {

        @Test
        @DisplayName("N개 그룹 — 종목 조회 단계 selectHealthy()는 정확히 1회만 호출(그룹마다 재산출 금지)")
        void sync_multipleGroups_selectHealthyCalledOnce() {
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            KisGroupListResponse.Group group3 =
                    new KisGroupListResponse.Group("003", "그룹3", "1", "3");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group1, group2, group3));
            when(kisWatchlistClient.fetchStocksByGroup(any(), any())).thenReturn(List.of());

            syncService.sync();

            // 그룹 3개여도 종목 조회 단계 헬스 스냅샷은 단위당 1회(콜드 프로브 비용 bound).
            verify(healthyKeySelector, times(1)).selectHealthy();
        }

        @Test
        @DisplayName("모든 그룹이 동일 세션 인스턴스를 공유하여 fetchStocksByGroup에 전달된다")
        void sync_allGroupsShareSameSession() {
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group1, group2));
            when(kisWatchlistClient.fetchStocksByGroup(any(), any())).thenReturn(List.of());

            syncService.sync();

            ArgumentCaptor<LeaseSession> sessionCaptor =
                    ArgumentCaptor.forClass(LeaseSession.class);
            verify(kisWatchlistClient, times(2)).fetchStocksByGroup(sessionCaptor.capture(), any());
            // 두 그룹 호출이 동일 세션 인스턴스를 공유(per-batch 1회 open).
            List<LeaseSession> captured = sessionCaptor.getAllValues();
            assertThat(captured.getFirst()).isSameAs(captured.get(1));
        }

        @Test
        @DisplayName("전 키 사망(빈 스냅샷) — 종목 조회 0회, 모든 그룹 skip 집계")
        void sync_allKeysDead_skipsAllGroupsAndNoFetch() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group1, group2));

            syncService.sync();

            // 빈 스냅샷 = 전 키 사망 → 종목 조회 0회, 2개 그룹 모두 skip 집계(failedGroupCount=2).
            verify(kisWatchlistClient, never()).fetchStocksByGroup(any(), any());
            verify(watchlistWriter).upsertAll(eq(List.of()), eq(2));
        }
    }
}
