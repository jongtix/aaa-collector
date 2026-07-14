package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.backfill.BackfillGroup;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventRepository;
import com.aaa.collector.stock.DividendBackfillFetch;
import com.aaa.collector.stock.DividendScheduleCollectionService;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SPEC-COLLECTOR-BACKFILL-009 통합 테스트 — DIVIDEND 백필 멱등 적재(AC-4) + SPLIT과의 진행 상태 분리(AC-6a, RD-1).
 *
 * <p>Testcontainers {@code mysql:8.4}로 native INSERT IGNORE 의미(H2 미재현)와 {@code backfill_status}
 * unique key {@code (target_type, target_code, data_table)} 분리를 검증한다. 전용 컨테이너(비공유) — {@link
 * org.springframework.transaction.annotation.Transactional} 메서드 롤백으로 격리한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("DIVIDEND 백필 통합 테스트 — 멱등 적재(AC-4)·SPLIT 진행 분리(AC-6a)")
@Tag("integration")
class DividendBackfillIntegrationTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @Autowired private DividendScheduleCollectionService dividendService;
    @Autowired private StockRepository stockRepository;
    @Autowired private CorporateEventRepository corporateEventRepository;
    @Autowired private BackfillStatusRepository backfillStatusRepository;

    private Stock savedStock(String symbol) {
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build());
    }

    /**
     * rate-only 배당 이벤트(RD-7): cash_amount=0·cash_rate=400.00·face_value=5000·event_subtype="결산".
     */
    private DividendBackfillFetch rateOnlyFetch(Stock stock) {
        CorporateEvent dividend =
                CorporateEvent.builder()
                        .stock(stock)
                        .eventType(EventType.DIVIDEND)
                        .eventDate(LocalDate.of(2015, 3, 31))
                        .eventSubtype("결산")
                        .cashAmount(BigDecimal.ZERO)
                        .cashRate(new BigDecimal("400.0000"))
                        .currencyCode("KRW")
                        .faceValue(5000L)
                        .build();
        return new DividendBackfillFetch(List.of(dividend), LocalDate.of(2015, 3, 31), 1);
    }

    @Nested
    @DisplayName("AC-4: 멱등 적재 — INSERT IGNORE, SQL 1142 미발생")
    class Idempotency {

        @Test
        @Transactional
        @DisplayName("동일 종목 배당 백필 2회 실행 — 행 수 불변(INSERT IGNORE 멱등)")
        void backfillTwiceLeavesRowCountUnchanged() {
            // Arrange
            Stock stock = savedStock("005930");

            // Act — 1회차 적재
            BackfillWindowResult first =
                    dividendService.persistWindowForBackfill(rateOnlyFetch(stock));

            // Assert — 1행 적재, 저장 행수·종료 입력 rawRowCount 모두 1
            assertThat(first.rowCount()).isEqualTo(1);
            assertThat(first.rawRowCount()).isEqualTo(1);
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);

            // Act — 2회차 재실행(동일 4컬럼 unique key)
            dividendService.persistWindowForBackfill(rateOnlyFetch(stock));

            // Assert — INSERT IGNORE로 중복 무시, 행 수 불변
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("AC-6a: SPLIT(corporate_events)과 DIVIDEND(corporate_events_dividend) 진행 분리 (RD-1)")
    class ProgressIsolation {

        private BackfillStatus seedAndFind(String symbol, String dataTable) {
            backfillStatusRepository.insertIgnoreSeed("STOCK", symbol, dataTable);
            return backfillStatusRepository.findAll().stream()
                    .filter(
                            s ->
                                    symbol.equals(s.getTargetCode())
                                            && dataTable.equals(s.getDataTable()))
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        @Transactional
        @DisplayName("동일 종목에 두 data_table 키가 별개 backfill_status 행으로 시딩되고 GROUP_A로 분류된다")
        void dividendAndSplitSeededAsSeparateRows() {
            // Arrange — 같은 종목(005930)에 SPLIT·DIVIDEND 두 키 시딩
            BackfillStatus split = seedAndFind("005930", "corporate_events");
            BackfillStatus dividend = seedAndFind("005930", "corporate_events_dividend");

            // Assert — 별개 행(unique key (target_type, target_code, data_table) 분리), 둘 다 GROUP_A
            assertThat(split.getId()).isNotEqualTo(dividend.getId());
            assertThat(BackfillGroup.ofDataTable(dividend.getDataTable()))
                    .isEqualTo(BackfillGroup.GROUP_A);
        }

        @Test
        @Transactional
        @DisplayName("DIVIDEND 키를 COMPLETED로 전이해도 같은 종목의 SPLIT 키 진행은 영향받지 않는다")
        void completingDividendDoesNotAffectSplitProgress() {
            // Arrange
            BackfillStatus split = seedAndFind("005930", "corporate_events");
            BackfillStatus dividend = seedAndFind("005930", "corporate_events_dividend");

            // Act — DIVIDEND 키만 COMPLETED로 전이
            BackfillStatus managedDividend =
                    backfillStatusRepository.findById(dividend.getId()).orElseThrow();
            managedDividend.advance(BackfillStatusType.COMPLETED, LocalDate.of(2015, 3, 31), 0, 1);
            backfillStatusRepository.saveAndFlush(managedDividend);

            // Assert — SPLIT 키는 여전히 PENDING(뒤섞이지 않음)
            BackfillStatus splitAfter =
                    backfillStatusRepository.findById(split.getId()).orElseThrow();
            assertThat(splitAfter.getStatus()).isEqualTo(BackfillStatusType.PENDING);
            assertThat(splitAfter.getLastCollectedDate()).isNull();
        }
    }
}
