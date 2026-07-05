package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.observability.RowFailureHandler;
import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventInserter;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.rights.OverseasSplitCollectionService.OverseasSplitCollectionResult;
import com.aaa.collector.stock.rights.OverseasSplitPrefetcher.OverseasSplitPrefetch;
import com.aaa.collector.stock.rights.OverseasSplitPrefetcher.TypeResult;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OverseasSplitCollectionService} 정기 수집 오케스트레이션 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-SPLIT-001
 * REQ-OSPLIT-001/003/010~013/070/071).
 *
 * <p>휴장일 skip(AC-10)·개장일 1회 수집(AC-11)·전역 fail-closed(AC-6)·유형 단위 절단/실패 격리(AC-6)·정상 적재(AC-1)를 실제
 * {@link OverseasSplitMapper}와 mock 프리페처로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasSplitCollectionService 정기 수집 단위 테스트")
class OverseasSplitCollectionServiceTest {

    private static final String SPLIT = OverseasSplitMapper.RGHT_TYPE_SPLIT;
    private static final String MERGE = OverseasSplitMapper.RGHT_TYPE_MERGE;

    @Mock private StockRepository stockRepository;
    @Mock private CorporateEventInserter corporateEventInserter;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private UsMarketOpenGate usMarketOpenGate;
    @Mock private OverseasSplitPrefetcher prefetcher;
    @Mock private LeaseSession session;

    @Captor private ArgumentCaptor<List<CorporateEvent>> inserterCaptor;

    private OverseasSplitCollectionService service;

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
        Mockito.lenient().when(usMarketOpenGate.isOpenDay(any())).thenReturn(true);
        Mockito.lenient().when(keyLeaseRegistry.openSession()).thenReturn(session);
        Mockito.lenient().when(session.isEmpty()).thenReturn(false);
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
            String pdno, String bassDt, String rt) {
        return new KisPeriodRightsResponse.PeriodRightsRow(
                bassDt,
                SPLIT,
                pdno,
                pdno + " INC",
                "512",
                "US0000000000",
                bassDt,
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

    private OverseasSplitPrefetch prefetch(TypeResult split, TypeResult merge) {
        return new OverseasSplitPrefetch(split, merge);
    }

    private void stubPrefetch(OverseasSplitPrefetch result) {
        when(prefetcher.prefetch(eq(session), eq(""), any(), any())).thenReturn(result);
    }

    @Nested
    @DisplayName("게이트·세션 fail-closed")
    class GateAndSession {

        @Test
        @DisplayName("AC-10: 휴장일 → skip, 프리페치·저장 없음")
        void marketClosed_skips() {
            when(usMarketOpenGate.isOpenDay(any())).thenReturn(false);

            OverseasSplitCollectionResult result = service.collect();

            assertThat(result.succeededRows()).isZero();
            verify(prefetcher, never()).prefetch(any(), any(), any(), any());
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("전 키 사망(빈 세션) → 전역 fail-closed, prefetchFailed=2, 저장 없음 (REQ-OSPLIT-070)")
        void emptySession_globalFailClosed() {
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock("AAPL")));
            when(session.isEmpty()).thenReturn(true);

            OverseasSplitCollectionResult result = service.collect();

            assertThat(result.prefetchFailed()).isEqualTo(2);
            verify(prefetcher, never()).prefetch(any(), any(), any(), any());
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("활성 종목 0개 → skip, 프리페치 없음")
        void noActiveStocks_skips() {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            OverseasSplitCollectionResult result = service.collect();

            assertThat(result.succeededRows()).isZero();
            verify(prefetcher, never()).prefetch(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("정상 수집·적재 (AC-1/AC-11)")
    class NormalCollection {

        @Test
        @DisplayName("AC-11: 개장일 SUCCESS 14 — AAPL 400.0 1건 INSERT IGNORE 적재, succeededRows=1")
        void openDay_splitSuccess_inserts() {
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock("AAPL")));
            stubPrefetch(
                    prefetch(
                            TypeResult.success(List.of(splitRow("AAPL", "20200831", "400.0"))),
                            TypeResult.success(List.of())));

            OverseasSplitCollectionResult result = service.collect();

            assertThat(result.succeededRows()).isEqualTo(1);
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            CorporateEvent saved = inserterCaptor.getValue().stream().findFirst().orElseThrow();
            assertThat(saved.getEventSubtype()).isEqualTo("분할");
            assertThat(saved.getStockRate())
                    .isEqualByComparingTo(new java.math.BigDecimal("4.0000"));
        }

        @Test
        @DisplayName("병합(15) SUCCESS — 별도 유형으로 매핑·적재")
        void mergeSuccess_insertsSeparately() {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock("GE")));
            KisPeriodRightsResponse.PeriodRightsRow geRow =
                    new KisPeriodRightsResponse.PeriodRightsRow(
                            "20210802",
                            MERGE,
                            "GE",
                            "GE",
                            "513",
                            "US3696043013",
                            "20210802",
                            "",
                            "",
                            "0",
                            "12.5",
                            "USD",
                            "",
                            "",
                            "",
                            "0",
                            "0",
                            "0",
                            "0",
                            "Y");
            stubPrefetch(
                    prefetch(TypeResult.success(List.of()), TypeResult.success(List.of(geRow))));

            OverseasSplitCollectionResult result = service.collect();

            assertThat(result.succeededRows()).isEqualTo(1);
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            assertThat(inserterCaptor.getValue().getFirst().getEventSubtype()).isEqualTo("병합");
        }
    }

    @Nested
    @DisplayName("유형 단위 fail-closed 격리 (AC-6, REQ-OSPLIT-071)")
    class TypeFailClosed {

        @Test
        @DisplayName("14 TRUNCATED + 15 SUCCESS — 15만 적재, prefetchTruncated=1")
        void splitTruncated_mergeInserted() {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock("GE")));
            KisPeriodRightsResponse.PeriodRightsRow geRow =
                    new KisPeriodRightsResponse.PeriodRightsRow(
                            "20210802",
                            MERGE,
                            "GE",
                            "GE",
                            "513",
                            "US3696043013",
                            "20210802",
                            "",
                            "",
                            "0",
                            "12.5",
                            "USD",
                            "",
                            "",
                            "",
                            "0",
                            "0",
                            "0",
                            "0",
                            "Y");
            stubPrefetch(prefetch(TypeResult.truncated(), TypeResult.success(List.of(geRow))));

            OverseasSplitCollectionResult result = service.collect();

            assertThat(result.prefetchTruncated()).isEqualTo(1);
            assertThat(result.succeededRows()).isEqualTo(1);
            verify(corporateEventInserter).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("14 FAILED + 15 FAILED — 저장 없음, prefetchFailed=2")
        void bothFailed_noInsert() {
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock("AAPL")));
            stubPrefetch(prefetch(TypeResult.failed(), TypeResult.failed()));

            OverseasSplitCollectionResult result = service.collect();

            assertThat(result.prefetchFailed()).isEqualTo(2);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }
    }

    @Nested
    @DisplayName("DB 격리 실패 흡수")
    class DbFailureAbsorbed {

        @Test
        @DisplayName("INSERT 격리 콜백 실패 — succeededRows 차감, skippedDbFailure 집계")
        void dbFailure_reducesSucceeded() {
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock("AAPL")));
            stubPrefetch(
                    prefetch(
                            TypeResult.success(List.of(splitRow("AAPL", "20200831", "400.0"))),
                            TypeResult.success(List.of())));
            SQLException toxic = new SQLException("dup", "23000", 1062);
            Mockito.doAnswer(
                            invocation -> {
                                List<CorporateEvent> rows = invocation.getArgument(0);
                                RowFailureHandler<CorporateEvent> handler =
                                        invocation.getArgument(1);
                                for (CorporateEvent e : rows) {
                                    handler.onFailure(e, toxic);
                                }
                                return null;
                            })
                    .when(corporateEventInserter)
                    .insertBatchIsolated(any(), any());

            OverseasSplitCollectionResult result = service.collect();

            assertThat(result.succeededRows()).isZero();
            assertThat(result.skippedDbFailure()).isEqualTo(1);
        }
    }
}
