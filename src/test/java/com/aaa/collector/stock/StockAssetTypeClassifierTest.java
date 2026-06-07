package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("StockAssetTypeClassifier 단위 테스트")
class StockAssetTypeClassifierTest {

    private final StockAssetTypeClassifier classifier = new StockAssetTypeClassifier();

    @Nested
    @DisplayName("classify — 시장별 기본 분류")
    class MarketBasedClassification {

        @Test
        @DisplayName("KRX 시장 — INDEX 반환")
        void classify_krxMarket_returnsIndex() {
            assertThat(classifier.classify(Market.KRX, "KRX001")).contains(AssetType.INDEX);
        }

        @Test
        @DisplayName("US 시장 — INDEX 반환")
        void classify_usMarket_returnsIndex() {
            assertThat(classifier.classify(Market.US, "US001")).contains(AssetType.INDEX);
        }

        @Test
        @DisplayName("KOSPI + M 접두사 종목코드 — COMMODITY 반환")
        void classify_kospiWithMPrefix_returnsCommodity() {
            assertThat(classifier.classify(Market.KOSPI, "M0001")).contains(AssetType.COMMODITY);
        }

        @Test
        @DisplayName("KOSPI + M 접두사 아닌 종목코드 — empty 반환 (KIS API 호출 필요)")
        void classify_kospiWithoutMPrefix_returnsEmpty() {
            assertThat(classifier.classify(Market.KOSPI, "005930")).isEmpty();
        }

        @Test
        @DisplayName("KOSDAQ — empty 반환 (KIS API 호출 필요)")
        void classify_kosdaq_returnsEmpty() {
            assertThat(classifier.classify(Market.KOSDAQ, "035420")).isEmpty();
        }

        @ParameterizedTest
        @CsvSource({"NYSE", "NASDAQ", "AMEX"})
        @DisplayName("미국 개별 시장 — empty 반환 (KIS API 호출 필요)")
        void classify_usIndividualMarkets_returnsEmpty(String marketName) {
            Market market = Market.valueOf(marketName);
            assertThat(classifier.classify(market, "AAPL")).isEmpty();
        }
    }

    @Nested
    @DisplayName("classify — WatchlistSyncService 회귀 검증")
    class RegressionVerification {

        @Test
        @DisplayName("KRX, US는 항상 INDEX — 종목코드에 무관하게")
        void classify_krxOrUs_alwaysIndex() {
            assertThat(classifier.classify(Market.KRX, "anything")).contains(AssetType.INDEX);
            assertThat(classifier.classify(Market.US, "anything")).contains(AssetType.INDEX);
        }

        @Test
        @DisplayName("KOSPI + M 접두사 → COMMODITY (금현물 등 원자재)")
        void classify_kospiMPrefix_commodityForGoldEtc() {
            assertThat(classifier.classify(Market.KOSPI, "M0025")).contains(AssetType.COMMODITY);
        }

        @Test
        @DisplayName("KOSPI + 빈 문자열 접두사 → empty (M 접두사 아님)")
        void classify_kospiEmptyPrefix_returnsEmpty() {
            assertThat(classifier.classify(Market.KOSPI, "")).isEmpty();
        }
    }
}
