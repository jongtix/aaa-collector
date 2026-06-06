package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisRateLimiter;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class WatchlistSyncServiceTest {

    @Mock private KisRateLimiter kisRateLimiter;
    @Mock private KisWatchlistClient kisWatchlistClient;
    @Mock private KisStockInfoClient kisStockInfoClient;
    @Mock private WatchlistWriter watchlistWriter;
    @InjectMocks private WatchlistSyncService watchlistSyncService;

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
    }

    @Nested
    @DisplayName("sync — 종목 수집 및 dedup")
    class StockCollection {

        @Test
        @DisplayName("단일 그룹 단일 종목 — upsertAll에 해당 종목 전달")
        void sync_singleGroupSingleStock_passesStockToUpsertAll() {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("J", "005930", "KRX", "삼성전자");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));
            when(kisStockInfoClient.fetchStockInfo(any(), any()))
                    .thenReturn(new StockInfo(null, null, null));

            // Act
            watchlistSyncService.sync();

            // Assert
            verify(watchlistWriter)
                    .upsertAll(
                            argThat(
                                    list ->
                                            list.size() == 1
                                                    && "005930".equals(list.getFirst().symbol())
                                                    && list.getFirst().market() == Market.KOSPI),
                            eq(0));
        }

        @Test
        @DisplayName("여러 그룹에 중복 종목 — dedup 처리로 upsertAll에 1개만 전달")
        void sync_duplicateAcrossGroups_deduplicatedBeforeUpsert() {
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
            when(kisStockInfoClient.fetchStockInfo(any(), any()))
                    .thenReturn(new StockInfo(null, null, null));

            // Act
            watchlistSyncService.sync();

            // Assert
            verify(watchlistWriter).upsertAll(argThat(list -> list.size() == 1), eq(0));
        }

        @Test
        @DisplayName("동일 종목코드 다른 거래소 — dedup 키 다르므로 upsertAll에 2개 전달")
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
            when(kisStockInfoClient.fetchStockInfo(any(), any()))
                    .thenReturn(new StockInfo(null, null, null));

            // Act
            watchlistSyncService.sync();

            // Assert
            ArgumentCaptor<List<ResolvedStock>> captor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(captor.capture(), eq(0));
            List<ResolvedStock> result = captor.getValue();
            assertThat(result).hasSize(2);
            assertThat(result)
                    .anyMatch(r -> "ACME".equals(r.symbol()) && r.market() == Market.NASDAQ);
            assertThat(result)
                    .anyMatch(r -> "ACME".equals(r.symbol()) && r.market() == Market.NYSE);
        }

        @Test
        @DisplayName("KRX 시장 종목 — stockInfo.assetType이 INDEX")
        void sync_krxMarket_stockInfoAssetTypeIsIndex() {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("U", "KRX001", "KRX", "KRX지수");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));

            // Act
            watchlistSyncService.sync();

            // Assert
            ArgumentCaptor<List<ResolvedStock>> indexCaptor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(indexCaptor.capture(), eq(0));
            assertThat(indexCaptor.getValue()).hasSize(1);
            assertThat(indexCaptor.getValue().getFirst().stockInfo().assetType())
                    .isEqualTo(AssetType.INDEX);
        }

        @Test
        @DisplayName("KOSPI M-prefix 종목코드 — stockInfo.assetType이 COMMODITY")
        void sync_kospiJongCodeStartsWithM_stockInfoAssetTypeIsCommodity() {
            // Arrange — 실측: 금현물 M04020000은 fid_mrkt_cls_code="J"(KOSPI), exch_code="KRX"
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("J", "M04020000", "KRX", "금 99.99_1Kg");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));

            // Act
            watchlistSyncService.sync();

            // Assert
            ArgumentCaptor<List<ResolvedStock>> commodityCaptor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(commodityCaptor.capture(), eq(0));
            assertThat(commodityCaptor.getValue()).hasSize(1);
            assertThat(commodityCaptor.getValue().getFirst().stockInfo().assetType())
                    .isEqualTo(AssetType.COMMODITY);
        }

        @Test
        @DisplayName("해외 M-prefix 종목코드(MSFT) — COMMODITY 미분류, upsertAll에 정상 전달")
        void sync_overseasJongCodeStartsWithM_notClassifiedAsCommodity() {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("FS", "MSFT", "NYS", "Microsoft");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));
            when(kisStockInfoClient.fetchStockInfo(any(), any()))
                    .thenReturn(new StockInfo(AssetType.STOCK, "Microsoft", null));

            // Act
            watchlistSyncService.sync();

            // Assert
            ArgumentCaptor<List<ResolvedStock>> captor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(captor.capture(), eq(0));
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().stockInfo().assetType())
                    .isNotEqualTo(AssetType.COMMODITY);
        }

        @Test
        @DisplayName("fetchStockInfo 예외 발생 — stockInfo null로 upsertAll에 전달")
        void sync_fetchStockInfoThrows_stockPassedWithNullStockInfo() {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));
            when(kisStockInfoClient.fetchStockInfo(any(), any()))
                    .thenThrow(new RestClientException("API 오류"));

            // Act
            watchlistSyncService.sync();

            // Assert
            ArgumentCaptor<List<ResolvedStock>> nullInfoCaptor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(nullInfoCaptor.capture(), eq(0));
            assertThat(nullInfoCaptor.getValue()).hasSize(1);
            assertThat(nullInfoCaptor.getValue().getFirst().stockInfo()).isNull();
        }

        @Test
        @DisplayName("fetchStocksByGroup 실패 그룹 — 해당 그룹 skip, 나머지 그룹 정상 처리")
        void sync_fetchStocksByGroupFails_failedGroupSkipped() {
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
            when(kisStockInfoClient.fetchStockInfo(any(), any()))
                    .thenReturn(new StockInfo(null, null, null));

            // Act
            watchlistSyncService.sync();

            // Assert — 001 그룹 실패해도 002 그룹의 종목 1개는 upsertAll에 전달
            ArgumentCaptor<List<ResolvedStock>> captor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(captor.capture(), eq(1));
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().symbol()).isEqualTo("005930");
        }

        @Test
        @DisplayName("알 수 없는 시장 코드 — Skipped 처리되어 upsertAll 목록에서 제외")
        void sync_unknownMarketCode_excludedFromUpsertAll() {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("UNKNOWN", "X001", "KRX", "알수없음");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));

            // Act
            watchlistSyncService.sync();

            // Assert
            verify(watchlistWriter).upsertAll(argThat(List::isEmpty), eq(0));
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
    @DisplayName("sync — failedGroupCount 전달")
    class FailedGroupCount {

        @Test
        @DisplayName("그룹 실패 존재 — upsertAll에 failedGroupCount=1 전달")
        void sync_groupFails_upsertAllCalledWithFailedCount() {
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
            when(kisStockInfoClient.fetchStockInfo(any(), any()))
                    .thenReturn(new StockInfo(null, null, null));

            // Act
            watchlistSyncService.sync();

            // Assert — failedGroupCount=1이 upsertAll에 전달되어야 함
            verify(watchlistWriter).upsertAll(argThat(list -> list.size() == 1), eq(1));
        }

        @Test
        @DisplayName("모든 그룹 성공 — upsertAll에 failedGroupCount=0 전달")
        void sync_allGroupsSucceed_upsertAllCalledWithZeroFailedCount() {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("J", "005930", "KRX", "삼성전자");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));

            // Act
            watchlistSyncService.sync();

            // Assert
            verify(watchlistWriter).upsertAll(any(), eq(0));
        }
    }

    @Nested
    @DisplayName("rate limit")
    class RateLimit {

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

            // Assert: 그룹 2개 → fetchStocksByGroup consume 2회
            verify(kisRateLimiter, times(2)).consume();
        }

        @Test
        @DisplayName("N개 종목(API 호출 대상) — fetchStockInfo 호출 전 consume이 N번 추가 호출된다")
        void sync_nStocks_consumeCalledAdditionalNTimesForStockInfo() throws InterruptedException {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock nas =
                    new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple");
            KisStockListByGroupResponse.Stock nys =
                    new KisStockListByGroupResponse.Stock("FS", "NVDA", "NYS", "NVIDIA");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(nas, nys));
            when(kisStockInfoClient.fetchStockInfo(any(), any()))
                    .thenReturn(new StockInfo(null, null, null));

            // Act
            watchlistSyncService.sync();

            // Assert: 그룹 1회 + 종목 2회 = 총 3회
            verify(kisRateLimiter, times(3)).consume();
        }

        @Test
        @DisplayName("INDEX/COMMODITY 종목 — fetchStockInfo 미호출이므로 consume 추가 호출 없음")
        void sync_indexAndCommodityStocks_noAdditionalConsumeForStockInfo()
                throws InterruptedException {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisStockListByGroupResponse.Stock krx =
                    new KisStockListByGroupResponse.Stock("U", "KRX001", "KRX", "KRX지수");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(krx));

            // Act
            watchlistSyncService.sync();

            // Assert: 그룹 1회만, stockInfo consume 없음
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
        @DisplayName("consume InterruptedException — 해당 그룹 skip, 나머지 그룹 정상 처리")
        void sync_consumeThrowsInterruptedException_affectedGroupSkipped()
                throws InterruptedException {
            // Arrange
            KisGroupListResponse.Group group1 =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisGroupListResponse.Group group2 =
                    new KisGroupListResponse.Group("002", "그룹2", "1", "2");
            // 두 그룹 모두 stub — thenAnswer(any())로 단일 stub 사용해 strict stub 미사용 예외 방지
            // (가상 스레드 스케줄러가 어떤 순서로 실행하든 interrupt된 그룹의 fetchStocksByGroup은 호출되지 않음)
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

            // Assert: 두 그룹 중 하나는 skip, 나머지 하나는 정상 처리 → 정확히 1종목
            ArgumentCaptor<List<ResolvedStock>> captor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(captor.capture(), eq(1));
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().symbol()).isIn("005930", "000660");
        }
    }
}
