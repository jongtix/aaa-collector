package com.aaa.collector.kis.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthyKeyRoundRobinDistributor 단위 테스트")
class HealthyKeyRoundRobinDistributorTest {

    private static final KisAccountCredential KEY1 =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential KEY2 =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");
    private static final KisAccountCredential KEY3 =
            new KisAccountCredential("pension", "33333333", "appkey-pension", "appsecret-pension");
    private static final KisAccountCredential KEY4 =
            new KisAccountCredential("stock", "44444444", "appkey-stock", "appsecret-stock");
    private static final KisAccountCredential KEY5 =
            new KisAccountCredential("dc", "55555555", "appkey-dc", "appsecret-dc");

    @Mock private HealthyKeySelector healthyKeySelector;

    private HealthyKeyRoundRobinDistributor distributor;

    @BeforeEach
    void setUp() {
        distributor = new HealthyKeyRoundRobinDistributor(healthyKeySelector);
    }

    private Stock stockOf(String symbol) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo("테스트_" + symbol)
                .market(Market.KOSPI)
                .assetType(AssetType.STOCK)
                .listedDate(LocalDate.of(2015, 1, 1))
                .build();
    }

    @Nested
    @DisplayName("distribute — 건강 키 라운드로빈 분산 (AC-1)")
    class DistributeHealthyKeys {

        @Test
        @DisplayName("5키 중 3키 건강, N개 종목 — 건강 키에만 분산, 죽은 키에는 할당 없음 (AC-1)")
        void fiveKeys_threeHealthy_distributedToHealthyOnly() {
            // Arrange
            List<KisAccountCredential> healthy = List.of(KEY1, KEY2, KEY3);
            when(healthyKeySelector.selectHealthy()).thenReturn(healthy);

            List<Stock> stocks =
                    List.of(
                            stockOf("005930"),
                            stockOf("000660"),
                            stockOf("035720"),
                            stockOf("005380"),
                            stockOf("068270"));

            // Act
            Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(stocks);

            // Assert: only healthy keys appear, dead keys (KEY4, KEY5) get 0 stocks
            assertThat(allocation).doesNotContainKey(KEY4);
            assertThat(allocation).doesNotContainKey(KEY5);

            int totalAllocated = allocation.values().stream().mapToInt(List::size).sum();
            assertThat(totalAllocated).isEqualTo(5);
        }

        @Test
        @DisplayName("건강 키 3개, 종목 3개 — stock[i] → healthyKeys.get(i%3) 결정적 규칙 (AC-1)")
        void threeHealthyKeys_threeStocks_deterministicRoundRobin() {
            // Arrange
            List<KisAccountCredential> healthy = List.of(KEY1, KEY2, KEY3);
            when(healthyKeySelector.selectHealthy()).thenReturn(healthy);

            Stock s0 = stockOf("005930");
            Stock s1 = stockOf("000660");
            Stock s2 = stockOf("035720");
            List<Stock> stocks = List.of(s0, s1, s2);

            // Act
            Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(stocks);

            // Assert: stock[0]→KEY1, stock[1]→KEY2, stock[2]→KEY3
            assertThat(allocation.get(KEY1)).containsExactly(s0);
            assertThat(allocation.get(KEY2)).containsExactly(s1);
            assertThat(allocation.get(KEY3)).containsExactly(s2);
        }

        @Test
        @DisplayName("건강 키 3개, 종목 6개 — 각 키에 정확히 2개씩 분산 (AC-1, AC-3)")
        void threeHealthyKeys_sixStocks_evenDistribution() {
            // Arrange
            List<KisAccountCredential> healthy = List.of(KEY1, KEY2, KEY3);
            when(healthyKeySelector.selectHealthy()).thenReturn(healthy);

            List<Stock> stocks =
                    List.of(
                            stockOf("A"),
                            stockOf("B"),
                            stockOf("C"),
                            stockOf("D"),
                            stockOf("E"),
                            stockOf("F"));

            // Act
            Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(stocks);

            // Assert: each healthy key gets exactly 2 stocks
            assertThat(allocation.get(KEY1)).hasSize(2);
            assertThat(allocation.get(KEY2)).hasSize(2);
            assertThat(allocation.get(KEY3)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("distribute — HealthyKeySelector 판정 위임 (AC-2)")
    class DelegatesHealthJudgment {

        @Test
        @DisplayName("selectHealthy() 결과를 그대로 사용 — 제외된 키는 할당 없음 (AC-2)")
        void delegatesHealthJudgmentToSelector() {
            // Arrange: selector excludes KEY4 and KEY5 (SafeMode / token failure)
            List<KisAccountCredential> healthy = List.of(KEY1, KEY2, KEY3);
            when(healthyKeySelector.selectHealthy()).thenReturn(healthy);

            List<Stock> stocks = List.of(stockOf("005930"), stockOf("000660"));

            // Act
            Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(stocks);

            // Assert: excluded keys get no allocation
            assertThat(allocation).doesNotContainKey(KEY4);
            assertThat(allocation).doesNotContainKey(KEY5);
            // All stocks go to healthy keys only
            int totalAllocated = allocation.values().stream().mapToInt(List::size).sum();
            assertThat(totalAllocated).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("distribute — 빈 건강 키 집합 (REQ-KEYDIST-004)")
    class EmptyHealthySet {

        @Test
        @DisplayName("selectHealthy() 빈 반환 — 빈 allocation 반환 (REQ-KEYDIST-004)")
        void emptyHealthyKeys_returnsEmptyAllocation() {
            // Arrange
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            List<Stock> stocks = List.of(stockOf("005930"), stockOf("000660"));

            // Act
            Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(stocks);

            // Assert: empty allocation — no policy enforcement in distributor
            assertThat(allocation).isEmpty();
        }

        @Test
        @DisplayName("빈 종목 목록 — 빈 allocation 반환")
        void emptyStocks_returnsEmptyAllocation() {
            // Arrange
            List<KisAccountCredential> healthy = List.of(KEY1, KEY2);
            when(healthyKeySelector.selectHealthy()).thenReturn(healthy);

            // Act
            Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(List.of());

            // Assert
            assertThat(allocation).isEmpty();
        }
    }

    @Nested
    @DisplayName("distribute — 결정성 (AC-1 REQ-KEYDIST-003)")
    class Determinism {

        @Test
        @DisplayName("동일 입력 → 동일 할당 (같은 입력이면 항상 같은 결과)")
        void sameInput_sameAllocation() {
            // Arrange
            List<KisAccountCredential> healthy = List.of(KEY1, KEY2, KEY3);
            when(healthyKeySelector.selectHealthy()).thenReturn(healthy);

            List<Stock> stocks = List.of(stockOf("005930"), stockOf("000660"), stockOf("035720"));

            // Act
            Map<KisAccountCredential, List<Stock>> first = distributor.distribute(stocks);
            // Reset mock and call again
            when(healthyKeySelector.selectHealthy()).thenReturn(healthy);
            Map<KisAccountCredential, List<Stock>> second = distributor.distribute(stocks);

            // Assert: same assignment
            assertThat(first.get(KEY1)).isEqualTo(second.get(KEY1));
            assertThat(first.get(KEY2)).isEqualTo(second.get(KEY2));
            assertThat(first.get(KEY3)).isEqualTo(second.get(KEY3));
        }
    }
}
