package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.StockAssetTypeClassifier;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

/**
 * SPEC-COLLECTOR-KISGATE-001 M4(T11) — 게이트 이전 후 회귀 테스트.
 *
 * <p>②단계 종목 해석을 {@code BatchRestExecutor}+{@code HealthyKeyRoundRobinDistributor}에서 {@code
 * GuardedKisExecutor}+{@code KeyLeaseRegistry} 게이트로 이전. graceful skip(REQ-WLSYNC-134)·per-batch
 * 스냅샷(REQ-KISGATE-006a)·예외별 종목 skip을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class WatchlistStockResolverTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    @Mock private KisStockInfoClient kisStockInfoClient;
    @Mock private StockAssetTypeClassifier stockAssetTypeClassifier;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    private WatchlistStockResolver watchlistStockResolver;

    @BeforeEach
    void setUp() {
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        watchlistStockResolver =
                new WatchlistStockResolver(
                        kisStockInfoClient,
                        stockAssetTypeClassifier,
                        guardedKisExecutor,
                        keyLeaseRegistry);
        // StockAssetTypeClassifier 실제 구현과 동일하게 동작하도록 lenient 스텁
        StockAssetTypeClassifier real = new StockAssetTypeClassifier();
        lenient()
                .when(stockAssetTypeClassifier.classify(any(), any()))
                .thenAnswer(inv -> real.classify(inv.getArgument(0), inv.getArgument(1)));
    }

    /** 건강 키 스냅샷을 1키(isa)로 고정한다. */
    private void singleHealthyKey() {
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
    }

    @Nested
    @DisplayName("게이트 경유 분산 + 커버리지 (REQ-KISGATE-001/006a)")
    class GateDistribution {

        @Test
        @DisplayName("5종목 — 전부 산출(커버리지 100%), per-batch selectHealthy 1회")
        void allStocksResolved_singleSnapshot() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(
                            new KisStockListByGroupResponse.Stock("FS", "S1", "NAS", "n1"),
                            new KisStockListByGroupResponse.Stock("FS", "S2", "NAS", "n2"),
                            new KisStockListByGroupResponse.Stock("FS", "S3", "NAS", "n3"),
                            new KisStockListByGroupResponse.Stock("FS", "S4", "NAS", "n4"),
                            new KisStockListByGroupResponse.Stock("FS", "S5", "NAS", "n5"));
            when(healthyKeySelector.selectHealthy())
                    .thenReturn(List.of(ISA, new KisAccountCredential("gold", "2", "k", "s")));
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenReturn(new StockInfo(AssetType.STOCK, "x", null));

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(5);
            // REQ-KISGATE-006a: 종목 수와 무관하게 스냅샷 1회
            verify(healthyKeySelector, times(1)).selectHealthy();
        }
    }

    @Nested
    @DisplayName("건강 키 0개 graceful skip (REQ-WLSYNC-134, REQ-KISGATE-024)")
    class GracefulSkip {

        @Test
        @DisplayName("빈 스냅샷 — 빈 목록 반환, fetchStockInfo 미호출, 예외 미전파")
        void emptySnapshot_returnsEmptyList_noException() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).isEmpty();
            verify(kisStockInfoClient, never()).fetchStockInfo(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("빈 입력 early guard (레거시 무로그 빈 목록 보존)")
    class EmptyInput {

        @Test
        @DisplayName("빈 rawStocks — 빈 목록 반환, selectHealthy/fetch 경로 미호출")
        void emptyRawStocks_returnsEmptyList_noFetch() {
            List<KisStockListByGroupResponse.Stock> emptyStocks = List.of();

            List<ResolvedStock> result = watchlistStockResolver.resolve(emptyStocks);

            assertThat(result).isEmpty();
            verify(healthyKeySelector, never()).selectHealthy();
            verify(kisStockInfoClient, never()).fetchStockInfo(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("정적 자산유형 분류 short-circuit (보존)")
    class StaticClassification {

        @Test
        @DisplayName("KRX 지수 — 정적 INDEX 분류로 API 미호출")
        void krxIndex_classifiedStatically_noApiCall() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("U", "KRX001", "KRX", "KRX지수"));
            singleHealthyKey();

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            verify(kisStockInfoClient, never()).fetchStockInfo(any(), any(), any());
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo().assetType()).isEqualTo(AssetType.INDEX);
        }

        @Test
        @DisplayName("KOSPI M-prefix 금현물 — 정적 COMMODITY 분류로 API 미호출")
        void kospiCommodity_classifiedStatically_noApiCall() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(
                            new KisStockListByGroupResponse.Stock(
                                    "J", "M04020000", "KRX", "금 99.99_1Kg"));
            singleHealthyKey();

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            verify(kisStockInfoClient, never()).fetchStockInfo(any(), any(), any());
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo().assetType()).isEqualTo(AssetType.COMMODITY);
        }
    }

    @Nested
    @DisplayName("알 수 없는 시장 코드 처리 (보존)")
    class UnknownMarket {

        @Test
        @DisplayName("알 수 없는 시장 코드 — Skipped 처리되어 결과에서 제외")
        void unknownMarketCode_excludedFromResult() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(
                            new KisStockListByGroupResponse.Stock(
                                    "UNKNOWN", "X001", "KRX", "알수없음"));
            singleHealthyKey();

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("예외별 종목 skip (graceful 보존)")
    class PerExceptionSkip {

        @Test
        @DisplayName("DateTimeParseException — 해당 종목 stockInfo null, 나머지 정상")
        void dateTimeParseException_thatStockNull_restContinues() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(
                            new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"),
                            new KisStockListByGroupResponse.Stock(
                                    "FS", "MSFT", "NAS", "Microsoft"));
            singleHealthyKey();
            when(kisStockInfoClient.fetchStockInfo(eq("AAPL"), any(), any()))
                    .thenThrow(new DateTimeParseException("invalid", "99999999", 0));
            when(kisStockInfoClient.fetchStockInfo(eq("MSFT"), any(), any()))
                    .thenReturn(new StockInfo(AssetType.STOCK, "Microsoft", null));

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(2);
            assertThat(result).anyMatch(r -> "MSFT".equals(r.symbol()) && r.stockInfo() != null);
            assertThat(result).anyMatch(r -> "AAPL".equals(r.symbol()) && r.stockInfo() == null);
        }

        @Test
        @DisplayName("KisTokenIssueException — 해당 종목만 skip, 나머지 정상")
        void tokenIssueException_onlyThatStockSkipped() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(
                            new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"),
                            new KisStockListByGroupResponse.Stock(
                                    "FS", "MSFT", "NAS", "Microsoft"));
            singleHealthyKey();
            when(kisStockInfoClient.fetchStockInfo(eq("AAPL"), any(), any()))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("dead")));
            when(kisStockInfoClient.fetchStockInfo(eq("MSFT"), any(), any()))
                    .thenReturn(new StockInfo(AssetType.STOCK, "Microsoft", null));

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(2);
            assertThat(result).anyMatch(r -> "MSFT".equals(r.symbol()) && r.stockInfo() != null);
            assertThat(result).anyMatch(r -> "AAPL".equals(r.symbol()) && r.stockInfo() == null);
        }

        @Test
        @DisplayName("RestClientException — 해당 종목 stockInfo null")
        void restClientException_thatStockNull() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            singleHealthyKey();
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenThrow(new RestClientException("API 오류"));

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo()).isNull();
        }

        @Test
        @DisplayName("KisApiBusinessException — 해당 종목 stockInfo null")
        void kisApiBusinessException_thatStockNull() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            singleHealthyKey();
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenThrow(new KisApiBusinessException("1", "EGW00999", "업무 오류"));

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo()).isNull();
        }

        @Test
        @DisplayName("IllegalStateException — 해당 종목 stockInfo null")
        void illegalStateException_thatStockNull() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            singleHealthyKey();
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenThrow(new IllegalStateException("잘못된 상태"));

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo()).isNull();
        }

        @Test
        @DisplayName("fetchStockInfo가 null 반환(BatchResult skip) — stockInfo null로 산출")
        void fetchStockInfoReturnsNull_stockInfoNull() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            singleHealthyKey();
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any())).thenReturn(null);

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo()).isNull();
        }
    }

    // @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
    @Nested
    @DisplayName("mket_id_cd 기반 권위 시장 전파 (보존)")
    class AuthoritativeMarketPropagation {

        @Test
        @DisplayName("KOSPI 종목 fid=UN + mket_id_cd=STK → ResolvedStock.market()==KOSPI")
        void kospiStock_fidUN_mketIdCdSTK_resolvedMarketIsKospi() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("UN", "005930", "KRX", "네이버"));
            singleHealthyKey();
            StockInfo infoWithKospi = new StockInfo(AssetType.STOCK, "NAVER", null, Market.KOSPI);
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any())).thenReturn(infoWithKospi);

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().market()).isEqualTo(Market.KOSPI);
        }

        @Test
        @DisplayName("KOSDAQ 종목 fid=J + mket_id_cd=KSQ → ResolvedStock.market()==KOSDAQ")
        void kosdaqStock_fidJ_mketIdCdKSQ_resolvedMarketIsKosdaq() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("J", "247540", "KRX", "에코프로비엠"));
            singleHealthyKey();
            StockInfo infoWithKosdaq =
                    new StockInfo(AssetType.STOCK, "EcoPro BM", null, Market.KOSDAQ);
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any())).thenReturn(infoWithKosdaq);

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().market()).isEqualTo(Market.KOSDAQ);
        }
    }

    @Nested
    @DisplayName("게이트 fetcher 경유 검증 (REQ-KISGATE-001/022)")
    class GateFetcher {

        @Test
        @DisplayName("API 종목 — fetcher가 게이트 경유 호출 후 BatchResult.success 반환")
        void apiStock_routedThroughGate() throws Exception {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            singleHealthyKey();
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenAnswer(
                            inv -> {
                                KisStockInfoClient.StockInfoFetcher fetcher = inv.getArgument(2);
                                BatchResult<KisDomesticStockInfoResponse> r =
                                        fetcher.fetch(
                                                uri -> null,
                                                "trId",
                                                KisDomesticStockInfoResponse.class);
                                assertThat(r.isSuccess()).isTrue();
                                return new StockInfo(AssetType.STOCK, "x", null);
                            });
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDomesticStockInfoResponse.class)))
                    .thenReturn(new KisDomesticStockInfoResponse("0", "M", "ok", null));

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(1);
            verify(guardedKisExecutor)
                    .execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDomesticStockInfoResponse.class));
        }

        @Test
        @DisplayName(
                "게이트 retryable 소진(KisRateLimitException) — fetcher가 BatchResult.skip 변환 → stockInfo null")
        void gateRetryableExhausted_fetcherSkips_stockInfoNull() throws Exception {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            singleHealthyKey();
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenAnswer(
                            inv -> {
                                KisStockInfoClient.StockInfoFetcher fetcher = inv.getArgument(2);
                                BatchResult<KisDomesticStockInfoResponse> r =
                                        fetcher.fetch(
                                                uri -> null,
                                                "trId",
                                                KisDomesticStockInfoResponse.class);
                                // 소진 → skip → KisStockInfoClient는 null 반환(graceful skip)
                                return r.isSuccess()
                                        ? new StockInfo(AssetType.STOCK, "x", null)
                                        : null;
                            });
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDomesticStockInfoResponse.class)))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201 소진"));

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo()).isNull();
        }

        @Test
        @DisplayName(
                "게이트 InterruptedException — fetcher가 BatchResult.skip 변환(전파 아님) → stockInfo null")
        void gateInterrupted_fetcherSkips_stockInfoNull() throws Exception {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            singleHealthyKey();
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenAnswer(
                            inv -> {
                                KisStockInfoClient.StockInfoFetcher fetcher = inv.getArgument(2);
                                BatchResult<KisDomesticStockInfoResponse> r =
                                        fetcher.fetch(
                                                uri -> null,
                                                "trId",
                                                KisDomesticStockInfoResponse.class);
                                return r.isSuccess()
                                        ? new StockInfo(AssetType.STOCK, "x", null)
                                        : null;
                            });
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDomesticStockInfoResponse.class)))
                    .thenThrow(new InterruptedException("테스트 인터럽트"));

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo()).isNull();
        }
    }
}
