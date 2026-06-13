package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisRateLimiter;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.grade.GradeClassificationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WatchlistSyncServiceTest {

    @Mock private KisRateLimiter kisRateLimiter;
    @Mock private KisWatchlistClient kisWatchlistClient;
    @Mock private WatchlistWriter watchlistWriter;
    @Mock private WatchlistStockResolver watchlistStockResolver;
    @Mock private GradeClassificationService gradeClassificationService;
    @InjectMocks private WatchlistSyncService watchlistSyncService;

    @BeforeEach
    void setUpResolver() {
        // ②단계는 resolver에 위임되므로, 기본적으로 입력 raw 목록을 그대로 통과시키는 stub을 둔다.
        // (개별 테스트가 필요 시 override). resolver의 ②단계 상세 행위는 WatchlistStockResolverTest가 검증한다.
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
    @DisplayName("sync — 그룹 목록")
    class Groups {

        @Test
        @DisplayName("빈 그룹 목록 — fetchStocksByGroup 미호출, upsertAll 빈 목록으로 호출")
        void sync_emptyGroups_noFetchStocksByGroupAndUpsertAllWithEmptyList() {
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of());

            watchlistSyncService.sync();

            verify(kisWatchlistClient, never()).fetchStocksByGroup(any());
            verify(watchlistWriter).upsertAll(eq(List.of()), eq(0));
        }

        @Test
        @DisplayName("여러 그룹 — 각 그룹별 fetchStocksByGroup 호출")
        void sync_multipleGroups_fetchesStocksForEachGroup() {
            // Arrange
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group1, group2));
            when(kisWatchlistClient.fetchStocksByGroup(any())).thenReturn(List.of());

            // Act
            watchlistSyncService.sync();

            // Assert
            verify(kisWatchlistClient, times(1)).fetchStocksByGroup("001");
            verify(kisWatchlistClient, times(1)).fetchStocksByGroup("002");
        }
    }

    @Nested
    @DisplayName("sync — ②단계 resolver 위임 (REQ-002, 시나리오 2)")
    class ResolverDelegation {

        @Test
        @DisplayName("dedup된 raw 목록으로 resolver.resolve 호출, 결과를 upsertAll에 전달")
        void sync_delegatesDedupedListToResolver_andPassesResultToUpsertAll() {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("J", "005930", "KRX", "삼성전자");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));

            List<ResolvedStock> resolved =
                    List.of(
                            new ResolvedStock(
                                    "005930",
                                    "삼성전자",
                                    Market.KOSPI,
                                    new StockInfo(null, null, null)));
            when(watchlistStockResolver.resolve(anyList())).thenReturn(resolved);

            // Act
            watchlistSyncService.sync();

            // Assert: resolver가 dedup된 raw 1종목으로 호출되고, 그 결과가 upsertAll로 전달됨
            verify(watchlistStockResolver)
                    .resolve(
                            argThat(
                                    list ->
                                            list.size() == 1
                                                    && "005930"
                                                            .equals(list.getFirst().jongCode())));
            verify(watchlistWriter).upsertAll(eq(resolved), eq(0));
        }

        @Test
        @DisplayName("여러 그룹 중복 종목 — dedup 후 resolver에 1개만 전달")
        void sync_duplicateAcrossGroups_deduplicatedBeforeResolver() {
            // Arrange
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("J", "005930", "KRX", "삼성전자");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group1, group2));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));
            when(kisWatchlistClient.fetchStocksByGroup("002")).thenReturn(List.of(item));

            // Act
            watchlistSyncService.sync();

            // Assert: dedup 키(jongCode:exchCode)가 같으므로 resolver에 1개만 전달
            verify(watchlistStockResolver).resolve(argThat(list -> list.size() == 1));
        }

        @Test
        @DisplayName("동일 종목코드 다른 거래소 — dedup 키 다르므로 resolver에 2개 전달")
        void sync_sameSymbolDifferentExchange_treatedAsSeparateEntries() {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock nas =
                    new KisStockListByGroupResponse.Stock("FS", "ACME", "NAS", "ACME Inc");
            KisStockListByGroupResponse.Stock nys =
                    new KisStockListByGroupResponse.Stock("FS", "ACME", "NYS", "ACME Inc");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(nas, nys));

            // Act
            watchlistSyncService.sync();

            // Assert
            verify(watchlistStockResolver).resolve(argThat(list -> list.size() == 2));
        }

        @Test
        @DisplayName("fetchStocksByGroup 실패 그룹 — 해당 그룹 skip, failedGroupCount 반영")
        void sync_fetchStocksByGroupFails_failedGroupCountPropagated() {
            // Arrange
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("J", "005930", "KRX", "삼성전자");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group1, group2));
            when(kisWatchlistClient.fetchStocksByGroup("001"))
                    .thenThrow(new RuntimeException("네트워크 오류"));
            when(kisWatchlistClient.fetchStocksByGroup("002")).thenReturn(List.of(item));

            // Act
            watchlistSyncService.sync();

            // Assert — 001 그룹 실패해도 002 그룹의 종목 1개는 resolver를 거쳐 upsertAll에 전달, failedGroupCount=1
            verify(watchlistStockResolver).resolve(argThat(list -> list.size() == 1));
            ArgumentCaptor<List<ResolvedStock>> captor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(captor.capture(), eq(1));
            assertThat(captor.getValue()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("①단계 rate limit (전역 kisRateLimiter, 무변경) — 시나리오 7")
    class Stage1RateLimit {

        @Test
        @DisplayName("N개 그룹 — fetchStocksByGroup 호출 전 consume이 N번 호출된다")
        void sync_nGroups_consumeCalledNTimesForGroupFetch() throws InterruptedException {
            // Arrange
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group1, group2));
            when(kisWatchlistClient.fetchStocksByGroup(any())).thenReturn(List.of());

            // Act
            watchlistSyncService.sync();

            // Assert: 그룹 2개 → fetchStocksByGroup consume 2회. ②단계는 전역 limiter 미사용(resolver에 위임).
            verify(kisRateLimiter, times(2)).consume();
        }

        @Test
        @DisplayName("②단계 종목 존재 — 전역 kisRateLimiter consume이 추가로 호출되지 않는다 (②단계 분리)")
        void sync_stage2Stocks_noAdditionalGlobalConsume() throws InterruptedException {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock nas =
                    new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple");
            KisStockListByGroupResponse.Stock nys =
                    new KisStockListByGroupResponse.Stock("FS", "NVDA", "NYS", "NVIDIA");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(nas, nys));

            // Act
            watchlistSyncService.sync();

            // Assert: 그룹 1회만 (②단계 종목은 resolver 위임, 전역 limiter 미경유)
            verify(kisRateLimiter, times(1)).consume();
        }

        @Test
        @DisplayName("빈 그룹 목록 — consume 미호출")
        void sync_emptyGroups_consumeNeverCalled() throws InterruptedException {
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of());

            watchlistSyncService.sync();

            verify(kisRateLimiter, never()).consume();
        }

        @Test
        @DisplayName("①단계 consume 후 release가 호출된다")
        void sync_groupFetch_releaseCalledAfterConsume() throws InterruptedException {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));

            // Act
            watchlistSyncService.sync();

            // Assert: ①단계 그룹fetch consume 1 → release 1 (②단계는 전역 limiter 미경유)
            verify(kisRateLimiter, times(1)).release();
        }

        @Test
        @DisplayName("①단계 consume InterruptedException — 해당 그룹 skip, 나머지 그룹 정상 처리")
        void sync_consumeThrowsInterruptedException_affectedGroupSkipped()
                throws InterruptedException {
            // Arrange
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            KisStockListByGroupResponse.Stock item1 =
                    new KisStockListByGroupResponse.Stock("J", "005930", "KRX", "삼성전자");
            KisStockListByGroupResponse.Stock item2 =
                    new KisStockListByGroupResponse.Stock("J", "000660", "KRX", "SK하이닉스");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group1, group2));
            when(kisWatchlistClient.fetchStocksByGroup(any()))
                    .thenAnswer(
                            inv ->
                                    "001".equals(inv.getArgument(0))
                                            ? List.of(item1)
                                            : List.of(item2));
            // 1회차 consume → InterruptedException(한 그룹 skip), 2회차 → doNothing(나머지 그룹 성공)
            doThrow(new InterruptedException()).doNothing().when(kisRateLimiter).consume();

            // Act
            watchlistSyncService.sync();

            // Assert: 두 그룹 중 하나는 skip → resolver에 1종목 전달, failedGroupCount=1
            verify(watchlistStockResolver).resolve(argThat(list -> list.size() == 1));
            verify(watchlistWriter).upsertAll(any(), eq(1));
        }
    }

    @Nested
    @DisplayName("시나리오 8 — classify 트리거 및 예외 격리 (SPEC-COLLECTOR-GRADE-002)")
    class ClassifyTrigger {

        @Test
        @DisplayName("시나리오 1: failedGroupCount>=1 — sync 후 classify 정확히 1회 호출")
        void sync_partialGroupFailure_classifyCalledOnce() {
            // Arrange: 일부 그룹 실패(failedGroupCount=1)
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("J", "005930", "KRX", "삼성전자");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group1, group2));
            when(kisWatchlistClient.fetchStocksByGroup("001"))
                    .thenThrow(new RuntimeException("rate-limit"));
            when(kisWatchlistClient.fetchStocksByGroup("002")).thenReturn(List.of(item));

            // Act
            watchlistSyncService.sync();

            // Assert: failedGroupCount=1이어도 classify 1회 호출
            verify(gradeClassificationService).classify();
        }

        @Test
        @DisplayName("시나리오 2: 전면 실패(빈 stocks, upsertAll early-return) — classify 여전히 호출")
        void sync_totalGroupFailure_classifyStillCalled() {
            // Arrange: 모든 그룹 조회 실패 → resolvedStocks 빈 목록 → upsertAll early-return
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of());

            // Act
            watchlistSyncService.sync();

            // Assert: early-return과 무관하게 classify 호출
            verify(gradeClassificationService).classify();
        }

        @Test
        @DisplayName("시나리오 3: classify 예외 — sync 미실패(예외 전파 없음)")
        void sync_classifyThrows_syncDoesNotThrow() {
            // Arrange
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of());
            doThrow(new RuntimeException("등급 분류 실패")).when(gradeClassificationService).classify();

            // Act & Assert
            assertThatCode(watchlistSyncService::sync).doesNotThrowAnyException();
        }
    }
}
