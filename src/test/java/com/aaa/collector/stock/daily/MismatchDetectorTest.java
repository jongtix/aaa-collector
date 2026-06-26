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
@DisplayName("MismatchDetector 단위 테스트 (ParsedOhlcvRow 파싱 1회 불변식 — REQ-INSERT-003)")
class MismatchDetectorTest {

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

    /** ParsedOhlcvRow — 검증 단계에서 이미 파싱된 행 (BigDecimal/Long/LocalDate 파싱 완료). */
    private ParsedOhlcvRow parsedRow(
            String date, String open, String high, String low, String close) {
        return new ParsedOhlcvRow(
                LocalDate.parse(date, java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                new BigDecimal(open),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                1_000_000L,
                75_000_000_000L);
    }

    private DailyOhlcv storedOhlcv(Stock stock, String date, String close) {
        return DailyOhlcv.builder()
                .stock(stock)
                .tradeDate(LocalDate.parse(date, java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
                .openPrice(new BigDecimal("74000"))
                .highPrice(new BigDecimal("76000"))
                .lowPrice(new BigDecimal("73000"))
                .closePrice(new BigDecimal(close))
                .volume(1_000_000L)
                .tradingValue(75_000_000_000L)
                .build();
    }

    @Nested
    @DisplayName("detectAndLog — ParsedOhlcvRow 기반 불일치 탐지 (AC-2/REQ-OHLCV2-011, REQ-INSERT-003)")
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
            // Arrange — ParsedOhlcvRow에는 이미 파싱된 값(80000)이 들어있다
            Stock stock = stockOf("005930");
            ParsedOhlcvRow mismatchedRow =
                    parsedRow("20260605", "74000", "76000", "73000", "80000");
            DailyOhlcv storedRow = storedOhlcv(stock, "20260605", "75000");
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(
                            null, List.of(LocalDate.of(2026, 6, 5))))
                    .thenReturn(List.of(storedRow));

            // Act
            detector.detectAndLog(null, "005930", List.of(mismatchedRow));

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
            Stock stock = stockOf("005930");
            ParsedOhlcvRow matchingRow = parsedRow("20260605", "74000", "76000", "73000", "75000");
            DailyOhlcv storedRow = storedOhlcv(stock, "20260605", "75000");
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(
                            null, List.of(LocalDate.of(2026, 6, 5))))
                    .thenReturn(List.of(storedRow));

            // Act
            detector.detectAndLog(null, "005930", List.of(matchingRow));

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
            // Arrange
            Stock stock = stockOf("005930");
            ParsedOhlcvRow row = parsedRow("20260605", "74000", "76000", "73000", "75000");
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
            detector.detectAndLog(null, "005930", List.of(row));

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
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(
                            null, List.of(LocalDate.of(2026, 6, 5))))
                    .thenReturn(List.of());

            detector.detectAndLog(
                    null,
                    "005930",
                    List.of(parsedRow("20260605", "74000", "76000", "73000", "75000")));

            List<ILoggingEvent> mismatchLogs =
                    listAppender.list.stream()
                            .filter(e -> e.getLevel() == Level.WARN)
                            .filter(e -> e.getMessage().contains("불일치"))
                            .toList();
            assertThat(mismatchLogs).isEmpty();
        }

        @Test
        @DisplayName("AC-2 — 2행 중 1행 불일치 → 불일치 행만 WARN 1건")
        void twoRows_oneMismatch_onlyMismatchedRowLogged() {
            // Arrange
            Stock stock = stockOf("005930");
            ParsedOhlcvRow normalRow = parsedRow("20260604", "74000", "76000", "73000", "75000");
            ParsedOhlcvRow mismatchedRow =
                    parsedRow("20260605", "74000", "76000", "73000", "80000");

            DailyOhlcv stored20260604 = storedOhlcv(stock, "20260604", "75000");
            DailyOhlcv stored20260605 = storedOhlcv(stock, "20260605", "75000");

            List<LocalDate> dates = List.of(LocalDate.of(2026, 6, 4), LocalDate.of(2026, 6, 5));
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(null, dates))
                    .thenReturn(List.of(stored20260604, stored20260605));

            // Act
            detector.detectAndLog(null, "005930", List.of(normalRow, mismatchedRow));

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
