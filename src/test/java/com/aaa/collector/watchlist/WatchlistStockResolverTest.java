package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.StockAssetTypeClassifier;
import com.aaa.collector.stock.enums.AssetType;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class WatchlistStockResolverTest {

    @Mock private KisStockInfoClient kisStockInfoClient;
    @Mock private StockAssetTypeClassifier stockAssetTypeClassifier;
    @Mock private HealthyKeyRoundRobinDistributor distributor;
    @Mock private BatchRestExecutor batchRestExecutor;
    @InjectMocks private WatchlistStockResolver watchlistStockResolver;

    private static KisAccountCredential cred(String alias) {
        return new KisAccountCredential(alias, "12345678", alias + "-key", alias + "-secret");
    }

    /**
     * 인라인 라운드로빈(레거시)과 동일한 분배 규칙({@code item[i] → healthyKeys.get(i % size)})을 distributor mock으로
     * 재현한다. resolver는 이제 distributor.distribute(items) 맵을 순회하므로, 테스트도 동일 규칙으로 맵을 구성한다.
     */
    private static Map<KisAccountCredential, List<KisStockListByGroupResponse.Stock>> roundRobin(
            List<KisAccountCredential> healthyKeys, List<KisStockListByGroupResponse.Stock> items) {
        if (healthyKeys.isEmpty() || items.isEmpty()) {
            return Map.of();
        }
        int size = healthyKeys.size();
        return IntStream.range(0, items.size())
                .boxed()
                .collect(
                        Collectors.groupingBy(
                                i -> healthyKeys.get(i % size),
                                Collectors.mapping(items::get, Collectors.toList())));
    }

    @BeforeEach
    void setUpClassifier() {
        // StockAssetTypeClassifier 실제 구현과 동일하게 동작하도록 lenient 스텁 설정
        StockAssetTypeClassifier real = new StockAssetTypeClassifier();
        lenient()
                .when(stockAssetTypeClassifier.classify(any(), any()))
                .thenAnswer(inv -> real.classify(inv.getArgument(0), inv.getArgument(1)));
    }

    @Nested
    @DisplayName("건강 키 라운드로빈 분산 (REQ-WLSYNC-131)")
    class RoundRobinDistribution {

        @Test
        @DisplayName("죽은 키 제외 시 건강 키에만 종목 배정 (죽은 키 호출 0회), 커버리지 100%")
        void deadKeyExcluded_onlyHealthyKeysUsed_fullCoverage() {
            // Arrange: 5키 중 3키만 건강. 5종목.
            List<KisAccountCredential> threeHealthy =
                    List.of(cred("isa"), cred("pension"), cred("stock"));
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(
                            new KisStockListByGroupResponse.Stock("FS", "S1", "NAS", "n1"),
                            new KisStockListByGroupResponse.Stock("FS", "S2", "NAS", "n2"),
                            new KisStockListByGroupResponse.Stock("FS", "S3", "NAS", "n3"),
                            new KisStockListByGroupResponse.Stock("FS", "S4", "NAS", "n4"),
                            new KisStockListByGroupResponse.Stock("FS", "S5", "NAS", "n5"));
            when(distributor.distribute(stocks)).thenReturn(roundRobin(threeHealthy, stocks));

            Queue<String> usedAliases = new ConcurrentLinkedQueue<>();
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenAnswer(
                            inv -> {
                                KisStockInfoClient.StockInfoFetcher fetcher = inv.getArgument(2);
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
            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            // Assert: 5종목 전부 산출(커버리지 100%)
            assertThat(result).hasSize(5);
            // REQ-WLSYNC-131 회귀 방지: 건강 키만 사용(죽은 키 미사용) + i % healthyKeys.size() 인덱스 매핑 정확성.
            //   3키(isa=0, pension=1, stock=2)에 5종목 라운드로빈: i=0→isa, 1→pension, 2→stock, 3→isa,
            // 4→pension → 정확히 {isa:2, pension:2, stock:1}.
            Map<String, Long> aliasCounts =
                    usedAliases.stream()
                            .collect(Collectors.groupingBy(a -> a, Collectors.counting()));
            assertThat(aliasCounts)
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("isa", 2L, "pension", 2L, "stock", 1L));
        }
    }

    @Nested
    @DisplayName("건강 키 0개 graceful skip (REQ-WLSYNC-134)")
    class GracefulSkip {

        @Test
        @DisplayName("distributor 빈 맵 반환 — 빈 목록 반환, fetchStockInfo 미호출, 예외 미전파")
        void emptyAllocation_returnsEmptyList_noException() {
            // Arrange: 건강 키 0개 → distributor 빈 맵
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            when(distributor.distribute(stocks)).thenReturn(Map.of());

            // Act
            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            // Assert: 빈 목록 + client 미호출 (graceful skip)
            assertThat(result).isEmpty();
            verify(kisStockInfoClient, never()).fetchStockInfo(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("빈 입력 early guard (레거시 무로그 빈 목록 보존)")
    class EmptyInput {

        @Test
        @DisplayName("빈 rawStocks + 건강 키 존재 — 빈 목록 반환, distributor/fetch 경로 미호출")
        void emptyRawStocks_withHealthyKeys_returnsEmptyList_noFetch() {
            // Arrange: 건강 키가 있는 정상 상태를 가정(distributor를 건강 키 보유 분산으로 스텁).
            // early guard로 인해 distribute는 실제 호출되지 않으나, "키 0개라서가 아니다"를 명시하기 위해 건강 키로 스텁한다.
            List<KisStockListByGroupResponse.Stock> emptyStocks = List.of();
            lenient()
                    .when(distributor.distribute(emptyStocks))
                    .thenReturn(roundRobin(List.of(cred("isa")), emptyStocks));

            // Act
            List<ResolvedStock> result = watchlistStockResolver.resolve(emptyStocks);

            // Assert: 빈 목록 + 분산/①단계 fetch 경로 미호출 — 오인 WARN 없는 무로그 빈 입력 경로
            assertThat(result).isEmpty();
            verify(distributor, never()).distribute(any());
            verify(kisStockInfoClient, never()).fetchStockInfo(any(), any(), any());
            verify(batchRestExecutor, never()).execute(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("정적 자산유형 분류 short-circuit")
    class StaticClassification {

        @Test
        @DisplayName("KRX 지수 — 정적 INDEX 분류로 API 미호출")
        void krxIndex_classifiedStatically_noApiCall() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("U", "KRX001", "KRX", "KRX지수"));
            when(distributor.distribute(stocks))
                    .thenReturn(roundRobin(List.of(cred("isa")), stocks));

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
            when(distributor.distribute(stocks))
                    .thenReturn(roundRobin(List.of(cred("isa")), stocks));

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            verify(kisStockInfoClient, never()).fetchStockInfo(any(), any(), any());
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo().assetType()).isEqualTo(AssetType.COMMODITY);
        }
    }

    @Nested
    @DisplayName("알 수 없는 시장 코드 처리")
    class UnknownMarket {

        @Test
        @DisplayName("알 수 없는 시장 코드 — Skipped 처리되어 결과에서 제외")
        void unknownMarketCode_excludedFromResult() {
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(
                            new KisStockListByGroupResponse.Stock(
                                    "UNKNOWN", "X001", "KRX", "알수없음"));
            when(distributor.distribute(stocks))
                    .thenReturn(roundRobin(List.of(cred("isa")), stocks));

            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("예외별 종목 skip (graceful)")
    class PerExceptionSkip {

        @Test
        @DisplayName("DateTimeParseException — 해당 종목 stockInfo null, 나머지 정상")
        void dateTimeParseException_thatStockNull_restContinues() {
            // Arrange
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(
                            new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"),
                            new KisStockListByGroupResponse.Stock(
                                    "FS", "MSFT", "NAS", "Microsoft"));
            when(distributor.distribute(stocks))
                    .thenReturn(roundRobin(List.of(cred("isa")), stocks));
            when(kisStockInfoClient.fetchStockInfo(eq("AAPL"), any(), any()))
                    .thenThrow(new DateTimeParseException("invalid", "99999999", 0));
            when(kisStockInfoClient.fetchStockInfo(eq("MSFT"), any(), any()))
                    .thenReturn(new StockInfo(AssetType.STOCK, "Microsoft", null));

            // Act
            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            // Assert: AAPL → null stockInfo, MSFT → 정상. 두 종목 모두 산출.
            assertThat(result).hasSize(2);
            assertThat(result).anyMatch(r -> "MSFT".equals(r.symbol()) && r.stockInfo() != null);
            assertThat(result).anyMatch(r -> "AAPL".equals(r.symbol()) && r.stockInfo() == null);
        }

        @Test
        @DisplayName("KisTokenIssueException — 해당 종목만 skip, 나머지 정상")
        void tokenIssueException_onlyThatStockSkipped() {
            // Arrange
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(
                            new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"),
                            new KisStockListByGroupResponse.Stock(
                                    "FS", "MSFT", "NAS", "Microsoft"));
            when(distributor.distribute(stocks))
                    .thenReturn(roundRobin(List.of(cred("isa")), stocks));
            when(kisStockInfoClient.fetchStockInfo(eq("AAPL"), any(), any()))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("dead")));
            when(kisStockInfoClient.fetchStockInfo(eq("MSFT"), any(), any()))
                    .thenReturn(new StockInfo(AssetType.STOCK, "Microsoft", null));

            // Act
            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            // Assert
            assertThat(result).hasSize(2);
            assertThat(result).anyMatch(r -> "MSFT".equals(r.symbol()) && r.stockInfo() != null);
            assertThat(result).anyMatch(r -> "AAPL".equals(r.symbol()) && r.stockInfo() == null);
        }

        @Test
        @DisplayName("RestClientException — 해당 종목 stockInfo null")
        void restClientException_thatStockNull() {
            // Arrange
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            when(distributor.distribute(stocks))
                    .thenReturn(roundRobin(List.of(cred("isa")), stocks));
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenThrow(new RestClientException("API 오류"));

            // Act
            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo()).isNull();
        }

        @Test
        @DisplayName("KisApiBusinessException — 해당 종목 stockInfo null")
        void kisApiBusinessException_thatStockNull() {
            // Arrange
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            when(distributor.distribute(stocks))
                    .thenReturn(roundRobin(List.of(cred("isa")), stocks));
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenThrow(new KisApiBusinessException("1", "EGW00999", "업무 오류"));

            // Act
            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo()).isNull();
        }

        @Test
        @DisplayName("IllegalStateException — 해당 종목 stockInfo null")
        void illegalStateException_thatStockNull() {
            // Arrange
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            when(distributor.distribute(stocks))
                    .thenReturn(roundRobin(List.of(cred("isa")), stocks));
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenThrow(new IllegalStateException("잘못된 상태"));

            // Act
            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo()).isNull();
        }

        @Test
        @DisplayName("fetchStockInfo가 null 반환(BatchResult skip) — stockInfo null로 산출")
        void fetchStockInfoReturnsNull_stockInfoNull() {
            // Arrange
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            when(distributor.distribute(stocks))
                    .thenReturn(roundRobin(List.of(cred("isa")), stocks));
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any())).thenReturn(null);

            // Act
            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockInfo()).isNull();
        }
    }

    @Nested
    @DisplayName("멀티키 fetcher 경유 검증")
    class MultikeyFetcher {

        @Test
        @DisplayName("API 종목 — 지정 credential로 batchRestExecutor 경유 호출")
        void apiStock_routedThroughBatchRestExecutorWithCredential() {
            // Arrange
            List<KisStockListByGroupResponse.Stock> stocks =
                    List.of(new KisStockListByGroupResponse.Stock("FS", "AAPL", "NAS", "Apple"));
            when(distributor.distribute(stocks))
                    .thenReturn(roundRobin(List.of(cred("isa")), stocks));
            when(kisStockInfoClient.fetchStockInfo(any(), any(), any()))
                    .thenAnswer(
                            inv -> {
                                KisStockInfoClient.StockInfoFetcher fetcher = inv.getArgument(2);
                                fetcher.fetch(
                                        uri -> null, "trId", KisDomesticStockInfoResponse.class);
                                return new StockInfo(AssetType.STOCK, "x", null);
                            });
            when(batchRestExecutor.execute(any(), any(), any(), any(), any()))
                    .thenReturn(
                            BatchResult.success(
                                    new KisDomesticStockInfoResponse("0", "M", "ok", null)));

            // Act
            List<ResolvedStock> result = watchlistStockResolver.resolve(stocks);

            // Assert: batchRestExecutor가 isa credential + AAPL symbol로 호출됨
            assertThat(result).hasSize(1);
            verify(batchRestExecutor)
                    .execute(
                            argThat(c -> "isa".equals(c.alias())), any(), any(), any(), eq("AAPL"));
        }
    }
}
