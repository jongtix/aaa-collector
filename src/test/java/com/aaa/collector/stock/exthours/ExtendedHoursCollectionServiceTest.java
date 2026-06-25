package com.aaa.collector.stock.exthours;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExtendedHoursCollectionService 단위 테스트")
class ExtendedHoursCollectionServiceTest {

    @Mock private StockRepository stockRepository;
    @Mock private YahooExtendedHoursClient yahooClient;
    @Mock private ExtendedHoursRepository extendedHoursRepository;
    @Mock private ExtendedHoursSleeper sleeper;

    private ExtendedHoursCollectionService service;

    private Stock aaplStock;
    private Stock msftStock;

    @BeforeEach
    void setUp() throws Exception {
        service =
                new ExtendedHoursCollectionService(
                        stockRepository, yahooClient, extendedHoursRepository, sleeper);

        aaplStock =
                Stock.builder()
                        .symbol("AAPL")
                        .nameKo("애플")
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(1980, 12, 12))
                        .build();

        msftStock =
                Stock.builder()
                        .symbol("MSFT")
                        .nameKo("마이크로소프트")
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(1986, 3, 13))
                        .build();
    }

    private ExtendedHoursRow buildRow(Stock stock, Session session, BigDecimal extPrice) {
        return new ExtendedHoursRow(
                stock.getId(),
                session,
                LocalDate.of(2026, 6, 25),
                extPrice,
                new BigDecimal("295.9500"),
                "YAHOO");
    }

    @Nested
    @DisplayName("collect — 대상 조회 검증")
    class TargetQuery {

        @Test
        @DisplayName("findAllActiveOverseasTradable() 호출 — 미국 활성 종목 조회")
        void collect_callsFindAllActiveOverseasTradable() {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            service.collect(Session.PRE);

            verify(stockRepository).findAllActiveOverseasTradable();
        }

        @Test
        @DisplayName("대상 0종목 — 외부 호출 없음, INFO 로그")
        void emptyStocks_noExternalCalls() {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            service.collect(Session.PRE);

            verify(yahooClient, never()).fetch(any(), any());
            verify(extendedHoursRepository, never())
                    .insertIgnoreDuplicate(
                            anyLong(), anyString(), any(), any(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("collect — 종목 간 딜레이 검증")
    class DelayBehavior {

        @Test
        @DisplayName("종목 2개 처리 — 딜레이 1회 (첫 종목 제외)")
        void twoStocks_sleeperCalledOnce() throws Exception {
            // Arrange
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(aaplStock, msftStock));
            when(yahooClient.fetch(eq(aaplStock), any()))
                    .thenReturn(
                            Optional.of(
                                    buildRow(aaplStock, Session.PRE, new BigDecimal("295.25"))));
            when(yahooClient.fetch(eq(msftStock), any()))
                    .thenReturn(
                            Optional.of(
                                    buildRow(msftStock, Session.PRE, new BigDecimal("420.10"))));

            // Act
            service.collect(Session.PRE);

            // Assert — 종목 2개: 첫 번째 전 딜레이 없음, 두 번째 전 딜레이 1회
            verify(sleeper, times(1)).sleep(anyLong());
        }

        @Test
        @DisplayName("종목 1개 처리 — 딜레이 없음")
        void oneStock_noSleeper() throws Exception {
            // Arrange
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(aaplStock));
            when(yahooClient.fetch(eq(aaplStock), any()))
                    .thenReturn(
                            Optional.of(
                                    buildRow(aaplStock, Session.PRE, new BigDecimal("295.25"))));

            // Act
            service.collect(Session.PRE);

            // Assert
            verify(sleeper, never()).sleep(anyLong());
        }
    }

    @Nested
    @DisplayName("collect — 예외 격리 검증")
    class ExceptionIsolation {

        @Test
        @DisplayName("첫 종목 예외 → 나머지 계속 처리")
        void firstStockException_continuesWithRemainingStocks() throws Exception {
            // Arrange
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(aaplStock, msftStock));
            when(yahooClient.fetch(eq(aaplStock), any()))
                    .thenThrow(new RuntimeException("AAPL 수집 오류"));
            when(yahooClient.fetch(eq(msftStock), any()))
                    .thenReturn(
                            Optional.of(
                                    buildRow(msftStock, Session.PRE, new BigDecimal("420.10"))));

            // Act — 예외 전파 없어야 함
            service.collect(Session.PRE);

            // Assert — MSFT는 저장됨
            verify(extendedHoursRepository, times(1))
                    .insertIgnoreDuplicate(
                            any(), eq("PRE"), any(), any(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("collect — ext_price ≤ 0 skip 검증")
    class PriceValidation {

        @Test
        @DisplayName("ext_price = 0 → skip, 저장 없음")
        void zeroPriceRow_skipped() {
            // Arrange
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(aaplStock));
            when(yahooClient.fetch(eq(aaplStock), any()))
                    .thenReturn(Optional.of(buildRow(aaplStock, Session.PRE, BigDecimal.ZERO)));

            // Act
            service.collect(Session.PRE);

            // Assert
            verify(extendedHoursRepository, never())
                    .insertIgnoreDuplicate(
                            anyLong(), anyString(), any(), any(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("collect — 멱등 저장 호출 검증")
    class IdempotentSave {

        @Test
        @DisplayName("정상 행 → insertIgnoreDuplicate 호출, session name 문자열 전달")
        void normalRow_callsInsertIgnoreDuplicate() {
            // Arrange
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(aaplStock));
            ExtendedHoursRow row = buildRow(aaplStock, Session.PRE, new BigDecimal("295.25"));
            when(yahooClient.fetch(eq(aaplStock), any())).thenReturn(Optional.of(row));

            // Act
            service.collect(Session.PRE);

            // Assert
            ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
            verify(extendedHoursRepository)
                    .insertIgnoreDuplicate(
                            any(),
                            sessionCaptor.capture(),
                            any(),
                            any(),
                            any(),
                            anyString(),
                            any());
            assertThat(sessionCaptor.getValue()).isEqualTo("PRE");
        }

        @Test
        @DisplayName("fetch가 empty → insertIgnoreDuplicate 미호출")
        void emptyFetchResult_noInsert() {
            // Arrange
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(aaplStock));
            when(yahooClient.fetch(eq(aaplStock), any())).thenReturn(Optional.empty());

            // Act
            service.collect(Session.PRE);

            // Assert
            verify(extendedHoursRepository, never())
                    .insertIgnoreDuplicate(
                            anyLong(), anyString(), any(), any(), any(), anyString(), any());
        }
    }
}
