package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisRateLimiter;
import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.StockAssetTypeClassifier;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.grade.GradeClassificationService;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
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
    @Mock private StockAssetTypeClassifier stockAssetTypeClassifier;
    @Mock private HealthyKeySelector healthyKeySelector;
    @Mock private BatchRestExecutor batchRestExecutor;
    @Mock private GradeClassificationService gradeClassificationService;
    @InjectMocks private WatchlistSyncService watchlistSyncService;

    private static final List<KisAccountCredential> FIVE_HEALTHY_KEYS =
            List.of(cred("isa"), cred("gold"), cred("pension"), cred("stock"), cred("dc"));

    private static KisAccountCredential cred(String alias) {
        return new KisAccountCredential(alias, "12345678", alias + "-key", alias + "-secret");
    }

    @BeforeEach
    void setUpClassifier() {
        // StockAssetTypeClassifier 실제 구현과 동일하게 동작하도록 lenient 스텁 설정
        StockAssetTypeClassifier real = new StockAssetTypeClassifier();
        lenient()
                .when(stockAssetTypeClassifier.classify(any(), any()))
                .thenAnswer(inv -> real.classify(inv.getArgument(0), inv.getArgument(1)));
        // ②단계 헬스 필터는 기본적으로 5키 모두 건강하다고 가정
        lenient().when(healthyKeySelector.selectHealthy()).thenReturn(FIVE_HEALTHY_KEYS);
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
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
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
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
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
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
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
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
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
        @DisplayName("fetchStockInfo가 null 반환(graceful skip) — stockInfo null로 upsertAll에 전달")
        void sync_fetchStockInfoReturnsNull_stockPassedWithNullStockInfo() {
            // Arrange — BatchResult skip 등으로 client가 null 반환
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any())).thenReturn(null);

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
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
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
    @DisplayName("②단계 멀티키 분산 (R3+R4)")
    class MultikeyDistribution {

        @Test
        @DisplayName("AC-5 — ②단계는 건강 키 라운드로빈으로 fetchStockInfo 호출, ①단계는 전역 kisRateLimiter 유지")
        void stage2_multikeyRoundRobin_stage1KeepsGlobalLimiter() throws InterruptedException {
            // Arrange: API 호출이 필요한 해외 종목 2개
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock s1 =
                    new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple");
            KisStockListByGroupResponse.Stock s2 =
                    new KisStockListByGroupResponse.Stock("FS", "NVDA", "NYS", "NVIDIA");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(s1, s2));
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenReturn(new StockInfo(AssetType.STOCK, "x", null));

            // Act
            watchlistSyncService.sync();

            // Assert: ②단계는 healthyKeySelector를 사용하고 client 3-arg 멀티키 경로 호출
            verify(healthyKeySelector).selectHealthy();
            verify(kisStockInfoClient, times(2)).fetchStockInfo(any(), any(), any());
            // ①단계만 전역 limiter consume (그룹 1회), ②단계는 전역 limiter 미사용
            verify(kisRateLimiter, times(1)).consume();
        }

        @Test
        @DisplayName("AC-6/AC-7 — 죽은 키 제외 시 건강 키에만 종목 배정 (죽은 키 호출 0회), 커버리지 100%")
        void deadKeyExcluded_onlyHealthyKeysUsed_fullCoverage() {
            // Arrange: 5키 중 3키만 건강. 5종목.
            List<KisAccountCredential> threeHealthy =
                    List.of(cred("isa"), cred("pension"), cred("stock"));
            when(healthyKeySelector.selectHealthy()).thenReturn(threeHealthy);

            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(
                            new KisStockListByGroupResponse.Stock("FS", "S1", "NAS", "n1"),
                            new KisStockListByGroupResponse.Stock("FS", "S2", "NAS", "n2"),
                            new KisStockListByGroupResponse.Stock("FS", "S3", "NAS", "n3"),
                            new KisStockListByGroupResponse.Stock("FS", "S4", "NAS", "n4"),
                            new KisStockListByGroupResponse.Stock("FS", "S5", "NAS", "n5"));
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(stocks);

            Queue<String> usedAliases = new ConcurrentLinkedQueue<>();
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenAnswer(
                            inv -> {
                                KisStockInfoClient.StockInfoFetcher fetcher = inv.getArgument(2);
                                // fetcher 내부에서 batchRestExecutor.execute(credential, ...)가 호출되어
                                // 어떤 키가 사용되는지 capture된다 (아래 batchRestExecutor 스텁 참조)
                                fetcher.fetch(
                                        uri -> null, "trId", KisDomesticStockInfoResponse.class);
                                return new StockInfo(AssetType.STOCK, "x", null);
                            });
            when(batchRestExecutor.execute(any(), any(), any(), any(), any()))
                    .thenAnswer(
                            inv -> {
                                KisAccountCredential c = inv.getArgument(0);
                                usedAliases.add(c.alias());
                                return BatchResult.success(
                                        new KisDomesticStockInfoResponse("0", "M", "ok", null));
                            });

            // Act
            watchlistSyncService.sync();

            // Assert: 5종목 전부 upsert(커버리지 100%)
            ArgumentCaptor<List<ResolvedStock>> captor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(captor.capture(), eq(0));
            assertThat(captor.getValue()).hasSize(5);
            // REQ-WLSYNC-131 회귀 방지: 건강 키만 사용(죽은 gold/dc 미사용) + i % healthyKeys.size() 인덱스 매핑 정확성.
            //   3키(isa=0, pension=1, stock=2)에 5종목 라운드로빈: i=0→isa, 1→pension, 2→stock, 3→isa,
            // 4→pension
            //   → 정확히 {isa:2, pension:2, stock:1}. 단일 단언이 커버리지·죽은키 제외·인덱스 분배를 모두 포함한다.
            Map<String, Long> aliasCounts =
                    usedAliases.stream()
                            .collect(Collectors.groupingBy(a -> a, Collectors.counting()));
            assertThat(aliasCounts)
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("isa", 2L, "pension", 2L, "stock", 1L));
        }

        @Test
        @DisplayName("AC-8 — 모든 키 죽음 시 ②단계 graceful skip (예외 미전파, ①단계 부분 동작 유지)")
        void allKeysDead_stage2GracefulSkip_noException() {
            // Arrange
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));

            // Act: 예외 없이 완료
            watchlistSyncService.sync();

            // Assert: ②단계 분산 skip — client 미호출, 빈 목록 upsert (①단계 기반 부분 동작 정책 유지)
            verify(kisStockInfoClient, never()).fetchStockInfo(any(), any(), any());
            verify(watchlistWriter).upsertAll(argThat(List::isEmpty), eq(0));
        }

        @Test
        @DisplayName("AC-9 — ②단계 종목 token 발급 실패(KisTokenIssueException) 시 해당 종목만 skip")
        void stage2_tokenIssueException_onlyThatStockSkipped() {
            // Arrange: 2종목 중 1종목이 token 발급 실패
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock s1 =
                    new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple");
            KisStockListByGroupResponse.Stock s2 =
                    new KisStockListByGroupResponse.Stock("FS", "MSFT", "NAS", "Microsoft");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(s1, s2));
            when(kisStockInfoClient.fetchStockInfo(eq("AAPL"), any(), any()))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("dead")));
            when(kisStockInfoClient.fetchStockInfo(eq("MSFT"), any(), any()))
                    .thenReturn(new StockInfo(AssetType.STOCK, "Microsoft", null));

            // Act
            watchlistSyncService.sync();

            // Assert: AAPL → null stockInfo, MSFT → 정상. ②단계 전체 미실패.
            ArgumentCaptor<List<ResolvedStock>> captor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(captor.capture(), eq(0));
            assertThat(captor.getValue()).hasSize(2);
            assertThat(captor.getValue())
                    .anyMatch(r -> "MSFT".equals(r.symbol()) && r.stockInfo() != null);
            assertThat(captor.getValue())
                    .anyMatch(r -> "AAPL".equals(r.symbol()) && r.stockInfo() == null);
        }

        @Test
        @DisplayName("AC-5b — sync()는 morning/afternoon 공유 경로이므로 호출될 때마다 ②단계 멀티키+헬스 필터를 거친다")
        void sharedSyncPath_alwaysUsesMultikeyAndHealthFilter() {
            // Arrange: 동일 sync()를 2회 호출(morning, afternoon 시뮬레이션). afternoon만 단일키로 분기하는 별도
            // 경로가 존재하지 않으므로 매 호출마다 healthyKeySelector와 멀티키 client가 사용된다.
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenReturn(new StockInfo(AssetType.STOCK, "x", null));

            // Act: morning 경로 + afternoon 경로 모두 동일 sync()
            watchlistSyncService.sync();
            watchlistSyncService.sync();

            // Assert: 2회 모두 헬스 필터 + 멀티키 client 경유 (afternoon-only 단일키 분기 없음)
            verify(healthyKeySelector, times(2)).selectHealthy();
            verify(kisStockInfoClient, times(2)).fetchStockInfo(any(), any(), any());
        }

        @Test
        @DisplayName("INDEX/COMMODITY 종목 — 정적 분류로 API 미호출 (fetchStockInfo client 미호출)")
        void staticClassifiedStock_noApiCall() {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "1", "1");
            KisStockListByGroupResponse.Stock krx =
                    new KisStockListByGroupResponse.Stock("U", "KRX001", "KRX", "KRX지수");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(krx));

            // Act
            watchlistSyncService.sync();

            // Assert: 정적 분류 종목은 멀티키 client 호출 없음
            verify(kisStockInfoClient, never()).fetchStockInfo(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("①단계 rate limit (전역 kisRateLimiter, 무변경)")
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

            // Assert: 그룹 2개 → fetchStocksByGroup consume 2회. ②단계는 전역 limiter 미사용.
            verify(kisRateLimiter, times(2)).consume();
        }

        @Test
        @DisplayName("②단계 API 종목 — 전역 kisRateLimiter consume이 추가로 호출되지 않는다 (②단계 분리)")
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
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenReturn(new StockInfo(null, null, null));

            // Act
            watchlistSyncService.sync();

            // Assert: 그룹 1회만 (②단계 종목 2개는 전역 limiter 미경유)
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
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenReturn(new StockInfo(AssetType.STOCK, "Apple", null));

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

            // Assert: 두 그룹 중 하나는 skip, 나머지 하나는 정상 처리 → 정확히 1종목
            ArgumentCaptor<List<ResolvedStock>> captor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(captor.capture(), eq(1));
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().symbol()).isIn("005930", "000660");
        }
    }

    @Nested
    @DisplayName("시나리오 1/2/3 — classify 트리거 및 예외 격리 (SPEC-COLLECTOR-GRADE-002)")
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
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenReturn(new StockInfo(null, null, null));

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

    @Nested
    @DisplayName("②단계 날짜 파싱 실패 처리")
    class DateParsing {

        @Test
        @DisplayName("fetchStockInfo에서 DateTimeParseException — 해당 종목 null, 나머지 계속")
        void sync_fetchStockInfoThrowsDateTimeParseException_stockSkippedRestContinues() {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock stock1 =
                    new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple");
            KisStockListByGroupResponse.Stock stock2 =
                    new KisStockListByGroupResponse.Stock("FS", "MSFT", "NAS", "Microsoft");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(stock1, stock2));
            when(kisStockInfoClient.fetchStockInfo(eq("AAPL"), any(), any()))
                    .thenThrow(new DateTimeParseException("invalid", "99999999", 0));
            when(kisStockInfoClient.fetchStockInfo(eq("MSFT"), any(), any()))
                    .thenReturn(new StockInfo(AssetType.STOCK, "Microsoft", null));

            // Act
            watchlistSyncService.sync();

            // Assert: AAPL → null stockInfo, MSFT → 정상. 두 종목 모두 upsertAll에 전달됨.
            ArgumentCaptor<List<ResolvedStock>> captor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(captor.capture(), eq(0));
            assertThat(captor.getValue()).hasSize(2);
            assertThat(captor.getValue())
                    .anyMatch(r -> "MSFT".equals(r.symbol()) && r.stockInfo() != null);
            assertThat(captor.getValue())
                    .anyMatch(r -> "AAPL".equals(r.symbol()) && r.stockInfo() == null);
        }

        @Test
        @DisplayName("RestClientException 발생 — 해당 종목 null로 처리")
        void sync_fetchStockInfoThrowsRestClientException_stockNull() {
            // Arrange
            KisGroupListResponse.Group group =
                    new KisGroupListResponse.Group("001", "그룹1", "2", "1");
            KisStockListByGroupResponse.Stock item =
                    new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple");
            when(kisWatchlistClient.fetchGroups()).thenReturn(List.of(group));
            when(kisWatchlistClient.fetchStocksByGroup("001")).thenReturn(List.of(item));
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenThrow(new RestClientException("API 오류"));

            // Act
            watchlistSyncService.sync();

            // Assert
            ArgumentCaptor<List<ResolvedStock>> captor = ArgumentCaptor.captor();
            verify(watchlistWriter).upsertAll(captor.capture(), eq(0));
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().stockInfo()).isNull();
        }
    }
}
