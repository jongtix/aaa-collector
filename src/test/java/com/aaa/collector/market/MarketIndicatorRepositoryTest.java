package com.aaa.collector.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.support.SharedMySqlContainer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("MarketIndicatorRepository 통합 테스트 (INSERT IGNORE 멱등, REQ-030/-031/-032)")
@Tag("integration")
class MarketIndicatorRepositoryTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @Autowired private MarketIndicatorRepository marketIndicatorRepository;

    private MarketIndicator buildVix(LocalDate date, BigDecimal close) {
        return MarketIndicator.builder()
                .indicatorCode(IndicatorCode.VIX)
                .tradeDate(date)
                .openValue(new BigDecimal("18.0000"))
                .highValue(new BigDecimal("19.0000"))
                .lowValue(new BigDecimal("17.0000"))
                .closeValue(close)
                .source("CBOE")
                .build();
    }

    private MarketIndicator buildUsdkrw(LocalDate date, BigDecimal close) {
        return MarketIndicator.builder()
                .indicatorCode(IndicatorCode.USDKRW)
                .tradeDate(date)
                .closeValue(close)
                .source("KOREAEXIM")
                .build();
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-030, REQ-032)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1건 저장됨")
        void newRow_insertsOne() {
            marketIndicatorRepository.insertIgnoreDuplicate(
                    buildVix(LocalDate.of(2026, 1, 2), new BigDecimal("16.5000")));

            assertThat(marketIndicatorRepository.countByIndicatorCode(IndicatorCode.VIX))
                    .isGreaterThanOrEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (indicator_code, trade_date) 중복 삽입 — 행 수 불변, 원본 값 보존 (REQ-032)")
        void duplicate_rowCountUnchanged_originalValuePreserved() {
            // Arrange
            LocalDate date = LocalDate.of(2026, 1, 3);
            BigDecimal originalClose = new BigDecimal("20.1234");
            marketIndicatorRepository.insertIgnoreDuplicate(buildVix(date, originalClose));
            long countAfterFirst =
                    marketIndicatorRepository.countByIndicatorCode(IndicatorCode.VIX);

            // Act — 동일 키로 다른 값 재삽입
            marketIndicatorRepository.insertIgnoreDuplicate(
                    buildVix(date, new BigDecimal("99.9999")));

            // Assert — 행 수 불변
            assertThat(marketIndicatorRepository.countByIndicatorCode(IndicatorCode.VIX))
                    .isEqualTo(countAfterFirst);

            // Assert — 원본 값 보존 (UPDATE가 발생하지 않았음)
            MarketIndicator saved =
                    marketIndicatorRepository.findAll().stream()
                            .filter(
                                    m ->
                                            m.getIndicatorCode() == IndicatorCode.VIX
                                                    && m.getTradeDate().equals(date))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getCloseValue()).isEqualByComparingTo(originalClose);
        }

        @Test
        @DisplayName("서로 다른 거래일 — 각각 독립 삽입")
        void differentDates_insertsDistinctRows() {
            long before = marketIndicatorRepository.countByIndicatorCode(IndicatorCode.USDKRW);

            marketIndicatorRepository.insertIgnoreDuplicate(
                    buildUsdkrw(LocalDate.of(2026, 1, 5), new BigDecimal("1380.0000")));
            marketIndicatorRepository.insertIgnoreDuplicate(
                    buildUsdkrw(LocalDate.of(2026, 1, 6), new BigDecimal("1385.0000")));

            assertThat(marketIndicatorRepository.countByIndicatorCode(IndicatorCode.USDKRW))
                    .isEqualTo(before + 2);
        }

        @Test
        @DisplayName("USDKRW open/high/low=NULL 저장 — close만 필수 (REQ-013)")
        void usdkrw_nullOhlc_onlyCloseRequired() {
            LocalDate date = LocalDate.of(2026, 1, 7);
            marketIndicatorRepository.insertIgnoreDuplicate(
                    buildUsdkrw(date, new BigDecimal("1390.0000")));

            MarketIndicator saved =
                    marketIndicatorRepository.findAll().stream()
                            .filter(
                                    m ->
                                            m.getIndicatorCode() == IndicatorCode.USDKRW
                                                    && m.getTradeDate().equals(date))
                            .findFirst()
                            .orElseThrow();

            assertThat(saved.getOpenValue()).isNull();
            assertThat(saved.getHighValue()).isNull();
            assertThat(saved.getLowValue()).isNull();
            assertThat(saved.getCloseValue()).isEqualByComparingTo("1390.0000");
            assertThat(saved.getSource()).isEqualTo("KOREAEXIM");
        }
    }

    @Nested
    @DisplayName("countByIndicatorCode — 지표 코드별 집계")
    class CountByIndicatorCode {

        @Test
        @DisplayName("insertIgnoreDuplicate 후 count 증가")
        void countIncrementsAfterInsert() {
            long before = marketIndicatorRepository.countByIndicatorCode(IndicatorCode.VIX);
            marketIndicatorRepository.insertIgnoreDuplicate(
                    buildVix(LocalDate.of(2026, 2, 1), new BigDecimal("15.0000")));
            assertThat(marketIndicatorRepository.countByIndicatorCode(IndicatorCode.VIX))
                    .isEqualTo(before + 1);
        }
    }

    @Nested
    @DisplayName("findMinTradeDateByIndicatorCode — VIX 백필 anchor 결정 (REQ-042, W-4, MA-02)")
    class FindMinTradeDate {

        @Test
        @DisplayName("저장된 VIX 행 중 최소 거래일 반환")
        void returnsMinTradeDate() {
            marketIndicatorRepository.insertIgnoreDuplicate(
                    buildVix(LocalDate.of(2000, 1, 10), new BigDecimal("25.0000")));
            marketIndicatorRepository.insertIgnoreDuplicate(
                    buildVix(LocalDate.of(2000, 1, 3), new BigDecimal("22.0000")));
            marketIndicatorRepository.insertIgnoreDuplicate(
                    buildVix(LocalDate.of(2000, 1, 20), new BigDecimal("28.0000")));

            Optional<LocalDate> result =
                    marketIndicatorRepository.findMinTradeDateByIndicatorCode(IndicatorCode.VIX);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(LocalDate.of(2000, 1, 3));
        }

        @Test
        @DisplayName("USDKRW 코드별 minDate 독립성 확인 — 코드별 격리")
        void minDate_isolatedByIndicatorCode() {
            marketIndicatorRepository.insertIgnoreDuplicate(
                    buildUsdkrw(LocalDate.of(2000, 2, 1), new BigDecimal("1380.0000")));

            // USDKRW minDate는 VIX와 무관함을 확인
            Optional<LocalDate> usdkrwResult =
                    marketIndicatorRepository.findMinTradeDateByIndicatorCode(IndicatorCode.USDKRW);
            assertThat(usdkrwResult).isPresent();
            assertThat(usdkrwResult.get()).isEqualTo(LocalDate.of(2000, 2, 1));
        }
    }
}
