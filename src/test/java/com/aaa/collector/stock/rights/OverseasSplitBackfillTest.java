package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventInserter;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.rights.OverseasSplitPrefetcher.OverseasSplitPrefetch;
import com.aaa.collector.stock.rights.OverseasSplitPrefetcher.TypeResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OverseasSplitCollectionService} 종목지정 백필 fetch/persist 단위 테스트 (AC-8,
 * REQ-OSPLIT-060/061/062).
 *
 * <p>정기 수집과 동일 dedup·÷100 정규화·매핑 재사용, 원본 행수(rawRowCount) 결정성, INSERT IGNORE persist, PDNO 파라미터화를
 * mock 프리페처 + 실제 {@link OverseasSplitMapper}로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasSplitCollectionService 종목지정 백필 fetch/persist 단위 테스트")
class OverseasSplitBackfillTest {

    private static final String SPLIT = OverseasSplitMapper.RGHT_TYPE_SPLIT;

    @Mock private StockRepository stockRepository;
    @Mock private CorporateEventInserter corporateEventInserter;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private UsMarketOpenGate usMarketOpenGate;
    @Mock private OverseasSplitPrefetcher prefetcher;
    @Mock private LeaseSession session;

    @Captor private ArgumentCaptor<List<CorporateEvent>> inserterCaptor;

    private OverseasSplitCollectionService service;

    private final LocalDate floor = LocalDate.of(1950, 1, 1);
    private final LocalDate today = LocalDate.of(2026, 6, 28);

    @BeforeEach
    void setUp() {
        service =
                new OverseasSplitCollectionService(
                        stockRepository,
                        corporateEventInserter,
                        keyLeaseRegistry,
                        usMarketOpenGate,
                        prefetcher,
                        new OverseasSplitMapper());
    }

    private Stock stock(String symbol) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo(symbol + "테스트")
                .market(Market.NASDAQ)
                .assetType(AssetType.STOCK)
                .active(true)
                .build();
    }

    private KisPeriodRightsResponse.PeriodRightsRow splitRow(
            String pdno, String bassDt, String acplBassDt, String rt) {
        return new KisPeriodRightsResponse.PeriodRightsRow(
                bassDt,
                SPLIT,
                pdno,
                pdno + " INC",
                "512",
                "US0000000000",
                acplBassDt,
                "",
                "",
                "0",
                rt,
                "USD",
                "",
                "",
                "",
                "0",
                "0",
                "0",
                "0",
                "Y");
    }

    private OverseasSplitPrefetch splitOnly(List<KisPeriodRightsResponse.PeriodRightsRow> rows) {
        return new OverseasSplitPrefetch(TypeResult.success(rows), TypeResult.success(List.of()));
    }

    private void stubBackfillPrefetch(String symbol, OverseasSplitPrefetch result) {
        when(prefetcher.prefetch(eq(session), eq(symbol), any(), any())).thenReturn(result);
    }

    @Nested
    @DisplayName("fetch — 매핑 재사용·rawRowCount 결정성 (AC-8, REQ-OSPLIT-061)")
    class FetchStage {

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName("AC-8: AAPL 4:1 분할 1건 → SPLIT·분할·rate=4.0000·event_date=2020-08-31")
        void reusesSplitMapping() {
            Stock aapl = stock("AAPL");
            stubBackfillPrefetch(
                    "AAPL", splitOnly(List.of(splitRow("AAPL", "20200831", "20200831", "400.0"))));

            OverseasSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(aapl, session, floor, today);

            assertThat(fetch.validRows()).hasSize(1);
            CorporateEvent e = fetch.validRows().getFirst();
            assertThat(e.getEventType()).isEqualTo(EventType.SPLIT);
            assertThat(e.getEventSubtype()).isEqualTo("분할");
            assertThat(e.getStockRate()).isEqualByComparingTo(new BigDecimal("4.0000"));
            assertThat(e.getEventDate()).isEqualTo(LocalDate.of(2020, 8, 31));
            assertThat(fetch.oldestRecordDate()).isEqualTo(LocalDate.of(2020, 8, 31));
        }

        @Test
        @DisplayName("AC-4: AAPL 3행(주말 2 + 평일 1) → dedup 1건, rawRowCount=3(원본 행수 결정적)")
        void aaplThreeRows_dedupToOne_rawRowCountThree() {
            Stock aapl = stock("AAPL");
            stubBackfillPrefetch(
                    "AAPL",
                    splitOnly(
                            List.of(
                                    splitRow("AAPL", "20200829", "20200827", "400.0"),
                                    splitRow("AAPL", "20200830", "20200828", "400.0"),
                                    splitRow("AAPL", "20200831", "20200831", "400.0"))));

            OverseasSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(aapl, session, floor, today);

            assertThat(fetch.validRows()).hasSize(1);
            assertThat(fetch.rawRowCount()).isEqualTo(3);
            assertThat(fetch.validRows().getFirst().getEventDate())
                    .isEqualTo(LocalDate.of(2020, 8, 31));
        }

        @Test
        @DisplayName("AC-12: CRWD 별개 분할 2건(300↔400) → 병합되지 않고 2건 보존")
        void crwdDistinctEvents_notMerged() {
            Stock crwd = stock("CRWD");
            stubBackfillPrefetch(
                    "CRWD",
                    splitOnly(
                            List.of(
                                    splitRow("CRWD", "20230815", "20230815", "300.0"),
                                    splitRow("CRWD", "20240819", "20240819", "400.0"))));

            OverseasSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(crwd, session, floor, today);

            assertThat(fetch.validRows()).hasSize(2);
            assertThat(fetch.validRows())
                    .extracting(CorporateEvent::getStockRate)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactlyInAnyOrder(new BigDecimal("3.0000"), new BigDecimal("4.0000"));
        }

        @Test
        @DisplayName("EC-3/AC-8: 분할 이력 0건 → validRows empty, rawRowCount=0")
        void emptyHistory_zero() {
            Stock aapl = stock("AAPL");
            stubBackfillPrefetch("AAPL", splitOnly(List.of()));

            OverseasSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(aapl, session, floor, today);

            assertThat(fetch.validRows()).isEmpty();
            assertThat(fetch.rawRowCount()).isZero();
            assertThat(fetch.oldestRecordDate()).isNull();
        }

        @Test
        @DisplayName("PDNO=심볼이 프리페처에 전달된다 (REQ-OSPLIT-061)")
        void backfillPassesSymbolAsPdno() {
            Stock aapl = stock("AAPL");
            stubBackfillPrefetch("AAPL", splitOnly(List.of()));

            service.fetchWindowForBackfill(aapl, session, floor, today);

            verify(prefetcher).prefetch(eq(session), eq("AAPL"), eq("19500101"), eq("20260628"));
        }
    }

    @Nested
    @DisplayName("persist — INSERT IGNORE 적재·행수 분리 (REQ-OSPLIT-060/062)")
    class PersistStage {

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName("AC-8: 단일 유효 분할 → rowCount=1·rawRowCount=1, insertBatch 1행")
        void singleValidSplit_persist() {
            Stock aapl = stock("AAPL");
            stubBackfillPrefetch(
                    "AAPL", splitOnly(List.of(splitRow("AAPL", "20200831", "20200831", "400.0"))));

            OverseasSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(aapl, session, floor, today);
            BackfillWindowResult result = service.persistWindowForBackfill(fetch);

            assertThat(result.rowCount()).isEqualTo(1);
            assertThat(result.rawRowCount()).isEqualTo(1);
            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2020, 8, 31));
            verify(corporateEventInserter).insertBatch(inserterCaptor.capture());
            assertThat(inserterCaptor.getValue()).hasSize(1);
        }

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName("AC-4: AAPL 3행 dedup → 저장 rowCount=1이나 종료 입력 rawRowCount=3(발산 결정적)")
        void dedupRowCountDivergesFromRawRowCount() {
            Stock aapl = stock("AAPL");
            stubBackfillPrefetch(
                    "AAPL",
                    splitOnly(
                            List.of(
                                    splitRow("AAPL", "20200829", "20200827", "400.0"),
                                    splitRow("AAPL", "20200830", "20200828", "400.0"),
                                    splitRow("AAPL", "20200831", "20200831", "400.0"))));

            OverseasSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(aapl, session, floor, today);
            BackfillWindowResult result = service.persistWindowForBackfill(fetch);

            assertThat(result.rowCount()).isEqualTo(1);
            assertThat(result.rawRowCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("EC-3: 빈 fetch → rowCount=0·rawRowCount=0, insertBatch 빈 목록")
        void emptyFetch_persist() {
            Stock aapl = stock("AAPL");
            stubBackfillPrefetch("AAPL", splitOnly(List.of()));

            OverseasSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(aapl, session, floor, today);
            BackfillWindowResult result = service.persistWindowForBackfill(fetch);

            assertThat(result.rowCount()).isZero();
            assertThat(result.rawRowCount()).isZero();
            assertThat(result.oldestTradeDate()).isNull();
            verify(corporateEventInserter).insertBatch(List.of());
        }
    }
}
