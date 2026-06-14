package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aaa.collector.stock.DailyOhlcv;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
@DisplayName("MismatchDetector 단위 테스트")
class MismatchDetectorTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    @Mock private DailyOhlcvRepository dailyOhlcvRepository;

    private MismatchDetector detector;

    @BeforeEach
    void setUp() {
        detector = new MismatchDetector(dailyOhlcvRepository);
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

    private KisDailyOhlcvResponse.DailyOhlcvRow validRow(String date) {
        return new KisDailyOhlcvResponse.DailyOhlcvRow(
                date, "75000", "74000", "76000", "73000", "1000000", "75000000000", "N");
    }

    private DailyOhlcv storedOhlcv(Stock stock, String date, String close) {
        return DailyOhlcv.builder()
                .stock(stock)
                .tradeDate(LocalDate.parse(date, DATE_FMT))
                .openPrice(new BigDecimal("74000"))
                .highPrice(new BigDecimal("76000"))
                .lowPrice(new BigDecimal("73000"))
                .closePrice(new BigDecimal(close))
                .volume(1_000_000L)
                .tradingValue(75_000_000_000L)
                .build();
    }

    @Nested
    @DisplayName("detectAndLog — 불일치 탐지 (AC-2/REQ-OHLCV2-011)")
    class MismatchDetection {

        private Logger detectorLogger;
        private ListAppender<ILoggingEvent> listAppender;

        @BeforeEach
        void attachLogAppender() {
            detectorLogger = (Logger) LoggerFactory.getLogger(MismatchDetector.class);
            listAppender = new ListAppender<>();
            listAppender.start();
            detectorLogger.addAppender(listAppender);
        }

        @AfterEach
        void detachLogAppender() {
            detectorLogger.detachAppender(listAppender);
            listAppender.stop();
        }

        @Test
        @DisplayName("AC-2 — 재조회값과 저장값 불일치(close 상이) → WARN 로그 1건")
        void mismatch_closePriceDiffers_emitsWarnLog() {
            // Arrange
            Stock stock = stockOf("005930");
            KisDailyOhlcvResponse.DailyOhlcvRow mismatchedRow =
                    new KisDailyOhlcvResponse.DailyOhlcvRow(
                            "20260605",
                            "80000",
                            "74000",
                            "76000",
                            "73000",
                            "1000000",
                            "75000000000",
                            "N");
            DailyOhlcv storedRow = storedOhlcv(stock, "20260605", "75000");
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(
                            null, List.of(LocalDate.of(2026, 6, 5))))
                    .thenReturn(List.of(storedRow));

            // Act
            detector.detectAndLog(null, "005930", List.of(mismatchedRow), DATE_FMT);

            // Assert
            List<ILoggingEvent> warnLogs =
                    listAppender.list.stream()
                            .filter(e -> e.getLevel() == Level.WARN)
                            .filter(e -> e.getMessage().contains("불일치"))
                            .toList();
            assertThat(warnLogs).hasSize(1);
        }

        @Test
        @DisplayName("AC-3 — 재조회값과 저장값 일치(6컬럼 모두) → WARN 로그 없음")
        void match_allSixColumns_noWarnLog() {
            // Arrange
            Stock stock = stockOf("005930");
            KisDailyOhlcvResponse.DailyOhlcvRow matchingRow = validRow("20260605");
            DailyOhlcv storedRow = storedOhlcv(stock, "20260605", "75000");
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(
                            null, List.of(LocalDate.of(2026, 6, 5))))
                    .thenReturn(List.of(storedRow));

            // Act
            detector.detectAndLog(null, "005930", List.of(matchingRow), DATE_FMT);

            // Assert
            List<ILoggingEvent> mismatchWarnLogs =
                    listAppender.list.stream()
                            .filter(e -> e.getLevel() == Level.WARN)
                            .filter(e -> e.getMessage().contains("불일치"))
                            .toList();
            assertThat(mismatchWarnLogs).isEmpty();
        }

        @Test
        @DisplayName("AC-2 — BigDecimal scale 차이(75000 vs 75000.0000)는 불일치 아님 — compareTo 사용")
        void match_bigDecimalScaleDifference_treatedAsEqual() {
            // Arrange — DB round-trips store 75000 as 75000.0000; compareTo must return 0
            Stock stock = stockOf("005930");
            KisDailyOhlcvResponse.DailyOhlcvRow row = validRow("20260605"); // close=75000
            DailyOhlcv storedRow =
                    DailyOhlcv.builder()
                            .stock(stock)
                            .tradeDate(LocalDate.of(2026, 6, 5))
                            .openPrice(new BigDecimal("74000.0000"))
                            .highPrice(new BigDecimal("76000.0000"))
                            .lowPrice(new BigDecimal("73000.0000"))
                            .closePrice(new BigDecimal("75000.0000"))
                            .volume(1_000_000L)
                            .tradingValue(75_000_000_000L)
                            .build();
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(
                            null, List.of(LocalDate.of(2026, 6, 5))))
                    .thenReturn(List.of(storedRow));

            // Act
            detector.detectAndLog(null, "005930", List.of(row), DATE_FMT);

            // Assert: scale 차이는 불일치 아님
            List<ILoggingEvent> mismatchLogs =
                    listAppender.list.stream()
                            .filter(e -> e.getLevel() == Level.WARN)
                            .filter(e -> e.getMessage().contains("불일치"))
                            .toList();
            assertThat(mismatchLogs).isEmpty();
        }

        @Test
        @DisplayName("AC-2 — 신규 행(DB에 없음) — 불일치 WARN 없음 (비교 대상 없으면 skip)")
        void newRow_noStoredEntry_noMismatchWarn() {
            // Arrange — no stored rows in DB
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(
                            null, List.of(LocalDate.of(2026, 6, 5))))
                    .thenReturn(List.of());

            // Act
            detector.detectAndLog(null, "005930", List.of(validRow("20260605")), DATE_FMT);

            // Assert
            List<ILoggingEvent> mismatchLogs =
                    listAppender.list.stream()
                            .filter(e -> e.getLevel() == Level.WARN)
                            .filter(e -> e.getMessage().contains("불일치"))
                            .toList();
            assertThat(mismatchLogs).isEmpty();
        }

        @Test
        @DisplayName("AC-2 — 2종목 중 1종목 불일치 → 불일치 종목만 WARN 1건")
        void twoRows_oneMismatch_onlyMismatchedRowLogged() {
            // Arrange
            Stock stock = stockOf("005930");
            KisDailyOhlcvResponse.DailyOhlcvRow normalRow = validRow("20260604");
            KisDailyOhlcvResponse.DailyOhlcvRow mismatchedRow =
                    new KisDailyOhlcvResponse.DailyOhlcvRow(
                            "20260605",
                            "80000",
                            "74000",
                            "76000",
                            "73000",
                            "1000000",
                            "75000000000",
                            "N");

            DailyOhlcv stored20260604 = storedOhlcv(stock, "20260604", "75000");
            DailyOhlcv stored20260605 =
                    storedOhlcv(stock, "20260605", "75000"); // close differs from 80000

            List<LocalDate> dates = List.of(LocalDate.of(2026, 6, 4), LocalDate.of(2026, 6, 5));
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(null, dates))
                    .thenReturn(List.of(stored20260604, stored20260605));

            // Act
            detector.detectAndLog(null, "005930", List.of(normalRow, mismatchedRow), DATE_FMT);

            // Assert
            List<ILoggingEvent> warnLogs =
                    listAppender.list.stream()
                            .filter(e -> e.getLevel() == Level.WARN)
                            .filter(e -> e.getMessage().contains("불일치"))
                            .toList();
            assertThat(warnLogs).hasSize(1);
        }
    }
}
