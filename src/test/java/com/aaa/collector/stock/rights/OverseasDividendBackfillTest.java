package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventInserter;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

/**
 * {@link OverseasDividendBackfillService} 종목지정 해외 현금배당 백필 fetch/persist 단위 테스트
 * (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001 REQ-ODW-050~055/060/070~072).
 *
 * <p>{@code rights-by-ice} 서브윈도우 청킹(REQ-ODW-051a/051b)·{@link OverseasRightsRowAccumulator} 매핑
 * 재사용(REQ-ODW-055)·rawRowCount 결정성(REQ-ODW-054)·fail-closed 예외 전파(REQ-ODW-060)를 mock {@link
 * OverseasRightsCollectionService}(패키지 전용 {@code fetch} 재사용)/{@link DividendAmountPrefetcher}로
 * 검증한다. PDNO 완전 일치 필터링(REQ-ODW-053)은 {@link DividendAmountPrefetcher}가 소유하므로 {@link
 * DividendAmountPrefetcherTest}가 커버한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasDividendBackfillService 종목지정 해외 현금배당 백필 fetch/persist 단위 테스트")
class OverseasDividendBackfillTest {

    @Mock private OverseasRightsCollectionService rightsCollectionService;
    @Mock private DividendAmountPrefetcher dividendAmountPrefetcher;
    @Mock private CorporateEventInserter corporateEventInserter;
    @Mock private LeaseSession session;

    @Captor private ArgumentCaptor<List<CorporateEvent>> inserterCaptor;
    @Captor private ArgumentCaptor<LocalDate> chunkStartCaptor;
    @Captor private ArgumentCaptor<LocalDate> chunkEndCaptor;

    private OverseasDividendBackfillService service;

    // 단일 청크로 유지되도록 24개월(BACKFILL_RIGHTS_CHUNK_MONTHS) 이내 범위 — 청킹 자체는 별도 전용 테스트에서 검증한다.
    private final LocalDate floor = LocalDate.of(2026, 1, 1);
    private final LocalDate today = LocalDate.of(2026, 6, 28);

    @BeforeEach
    void setUp() {
        service =
                new OverseasDividendBackfillService(
                        rightsCollectionService, dividendAmountPrefetcher, corporateEventInserter);
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

    private KisOverseasRightsResponse.RightsRow cashDividendRow(
            String recordDt, String divLockDt, String payDt) {
        return new KisOverseasRightsResponse.RightsRow(
                "20260501", "현금배당", divLockDt, payDt, recordDt, "", "", "", "", "", "", "");
    }

    private KisOverseasRightsResponse response(List<KisOverseasRightsResponse.RightsRow> rows) {
        return new KisOverseasRightsResponse("0", "MCA00000", "정상", rows);
    }

    private DividendAmountItem item(String rghtTypeCd, String amount) {
        return new DividendAmountItem(
                rghtTypeCd,
                new BigDecimal(amount),
                new BigDecimal("0.0000"),
                new BigDecimal("0.0000"),
                "USD");
    }

    private DividendAmountPrefetch confirmedPrefetch(String symbol, LocalDate acplBassDt) {
        Map<DividendAmountKey, List<DividendAmountItem>> map =
                Map.of(
                        new DividendAmountKey(symbol, acplBassDt),
                        List.of(item(DividendAmountPrefetcher.RIGHT_TYPE_GENERAL, "0.50000")));
        return new DividendAmountPrefetch(map, Set.of(), 0, 0);
    }

    private DividendAmountPrefetch emptyPrefetch() {
        return new DividendAmountPrefetch(Map.of(), Set.of(), 0, 0);
    }

    private void stubRightsByIce(List<KisOverseasRightsResponse.RightsRow> rows)
            throws InterruptedException {
        when(rightsCollectionService.fetch(eq(session), eq("KO"), eq(floor), eq(today)))
                .thenReturn(response(rows));
    }

    @Nested
    @DisplayName("fetch — 매핑 재사용·rawRowCount 결정성 (REQ-ODW-054/055)")
    class FetchStage {

        @Test
        @DisplayName("REQ-ODW-055: 단일 청크 내 확정 매칭 현금배당 1건 → validRows 1건, DIVIDEND 매핑 재사용")
        void confirmedDividend_mappedViaAccumulator() throws InterruptedException {
            Stock ko = stock("KO");
            LocalDate recordDt = LocalDate.of(2026, 5, 11);
            stubRightsByIce(List.of(cashDividendRow("20260511", "20260509", "20260601")));
            when(dividendAmountPrefetcher.prefetchForBackfill(session, "KO", floor, today))
                    .thenReturn(confirmedPrefetch("KO", recordDt));

            OverseasDividendBackfillFetch fetch =
                    service.fetchWindowForBackfill(ko, session, floor, today);

            assertThat(fetch.validRows()).hasSize(1);
            CorporateEvent e = fetch.validRows().getFirst();
            assertThat(e.getEventType()).isEqualTo(EventType.DIVIDEND);
            assertThat(e.getEventDate()).isEqualTo(recordDt);
            assertThat(fetch.oldestRecordDate()).isEqualTo(recordDt);
            assertThat(fetch.rawRowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName(
                "REQ-ODW-054: 미확정(defer) 회차도 rawRowCount에는 포함되나 validRows에는 반영되지 않는다"
                        + "(rawRowCount·validRows 결정적 분리)")
        void unconfirmedRow_countedInRawRowCount_notInValidRows() throws InterruptedException {
            Stock ko = stock("KO");
            LocalDate confirmedDt = LocalDate.of(2026, 2, 11);
            stubRightsByIce(
                    List.of(
                            cashDividendRow("20260211", "20260209", "20260301"),
                            cashDividendRow("20260511", "20260509", "20260601")));
            when(dividendAmountPrefetcher.prefetchForBackfill(session, "KO", floor, today))
                    .thenReturn(confirmedPrefetch("KO", confirmedDt));

            OverseasDividendBackfillFetch fetch =
                    service.fetchWindowForBackfill(ko, session, floor, today);

            assertThat(fetch.validRows()).hasSize(1);
            assertThat(fetch.validRows().getFirst().getEventDate()).isEqualTo(confirmedDt);
            assertThat(fetch.rawRowCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("EC: 이력 0건 → validRows empty, rawRowCount=0, oldestRecordDate=null")
        void emptyHistory_zero() throws InterruptedException {
            Stock ko = stock("KO");
            stubRightsByIce(List.of());
            when(dividendAmountPrefetcher.prefetchForBackfill(session, "KO", floor, today))
                    .thenReturn(emptyPrefetch());

            OverseasDividendBackfillFetch fetch =
                    service.fetchWindowForBackfill(ko, session, floor, today);

            assertThat(fetch.validRows()).isEmpty();
            assertThat(fetch.rawRowCount()).isZero();
            assertThat(fetch.oldestRecordDate()).isNull();
        }

        @Test
        @DisplayName("REQ-ODW-051a/051b: 2년 초과 범위는 서브윈도우로 청킹되며 인접 청크는 경계일 1일을 중첩한다")
        void wideRange_chunkedWithOneDayOverlap() throws InterruptedException {
            Stock ko = stock("KO");
            LocalDate wideFrom = LocalDate.of(2020, 1, 1);
            LocalDate wideTo = LocalDate.of(2024, 6, 1);
            when(rightsCollectionService.fetch(
                            eq(session),
                            eq("KO"),
                            chunkStartCaptor.capture(),
                            chunkEndCaptor.capture()))
                    .thenReturn(response(List.of()));
            when(dividendAmountPrefetcher.prefetchForBackfill(session, "KO", wideFrom, wideTo))
                    .thenReturn(emptyPrefetch());

            service.fetchWindowForBackfill(ko, session, wideFrom, wideTo);

            // 3개 청크: [2020-01-01,2022-01-01] → [2022-01-01,2024-01-01] → [2024-01-01,2024-06-01]
            verify(rightsCollectionService, times(3)).fetch(eq(session), eq("KO"), any(), any());
            assertThat(chunkStartCaptor.getAllValues())
                    .containsExactly(
                            LocalDate.of(2020, 1, 1),
                            LocalDate.of(2022, 1, 1),
                            LocalDate.of(2024, 1, 1));
            assertThat(chunkEndCaptor.getAllValues())
                    .containsExactly(
                            LocalDate.of(2022, 1, 1),
                            LocalDate.of(2024, 1, 1),
                            LocalDate.of(2024, 6, 1));
        }

        @Test
        @DisplayName("REQ-ODW-060: rights-by-ice 청크 실패 → 부분 데이터로 폐기하지 않고 예외 전파(재시도 유도)")
        void rightsByIceChunkFailure_propagatesException() throws InterruptedException {
            Stock ko = stock("KO");
            when(rightsCollectionService.fetch(eq(session), eq("KO"), eq(floor), eq(today)))
                    .thenThrow(new KisRateLimitException("alias-1", "재시도 소진"));

            assertThatThrownBy(() -> service.fetchWindowForBackfill(ko, session, floor, today))
                    .isInstanceOf(OverseasDividendBackfillPrefetchFailedException.class)
                    .hasCauseInstanceOf(KisRateLimitException.class);
        }

        @Test
        @DisplayName("REQ-ODW-060: rights-by-ice RestClientException → rawRowCount 조작 없이 예외 전파")
        void rightsByIceRestClientException_propagatesException() throws InterruptedException {
            Stock ko = stock("KO");
            when(rightsCollectionService.fetch(eq(session), eq("KO"), eq(floor), eq(today)))
                    .thenThrow(new RestClientException("connection reset"));

            assertThatThrownBy(() -> service.fetchWindowForBackfill(ko, session, floor, today))
                    .isInstanceOf(OverseasDividendBackfillPrefetchFailedException.class);
        }

        @Test
        @DisplayName("REQ-ODW-060: CTRGT011R 프리페치 절단/실패 → rights-by-ice 성공해도 fetch 전체 실패로 전파")
        void ctrgt011rDegraded_propagatesException() throws InterruptedException {
            Stock ko = stock("KO");
            stubRightsByIce(List.of(cashDividendRow("20260511", "20260509", "20260601")));
            when(dividendAmountPrefetcher.prefetchForBackfill(session, "KO", floor, today))
                    .thenReturn(new DividendAmountPrefetch(Map.of(), Set.of(), 1, 0));

            assertThatThrownBy(() -> service.fetchWindowForBackfill(ko, session, floor, today))
                    .isInstanceOf(OverseasDividendBackfillPrefetchFailedException.class);
        }
    }

    @Nested
    @DisplayName("persist — INSERT IGNORE 적재·행수 분리 (REQ-ODW-070/071)")
    class PersistStage {

        @Test
        @DisplayName("단일 유효 배당 → rowCount=1·rawRowCount=1, insertBatch 1행")
        void singleValidDividend_persist() throws InterruptedException {
            Stock ko = stock("KO");
            LocalDate recordDt = LocalDate.of(2026, 5, 11);
            stubRightsByIce(List.of(cashDividendRow("20260511", "20260509", "20260601")));
            when(dividendAmountPrefetcher.prefetchForBackfill(session, "KO", floor, today))
                    .thenReturn(confirmedPrefetch("KO", recordDt));

            OverseasDividendBackfillFetch fetch =
                    service.fetchWindowForBackfill(ko, session, floor, today);
            BackfillWindowResult result = service.persistWindowForBackfill(fetch);

            assertThat(result.rowCount()).isEqualTo(1);
            assertThat(result.rawRowCount()).isEqualTo(1);
            assertThat(result.oldestTradeDate()).isEqualTo(recordDt);
            verify(corporateEventInserter).insertBatch(inserterCaptor.capture());
            assertThat(inserterCaptor.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("미확정 회차 defer → 저장 rowCount=1이나 종료 입력 rawRowCount=2(발산 결정적)")
        void deferredRow_rowCountDivergesFromRawRowCount() throws InterruptedException {
            Stock ko = stock("KO");
            LocalDate confirmedDt = LocalDate.of(2026, 2, 11);
            stubRightsByIce(
                    List.of(
                            cashDividendRow("20260211", "20260209", "20260301"),
                            cashDividendRow("20260511", "20260509", "20260601")));
            when(dividendAmountPrefetcher.prefetchForBackfill(session, "KO", floor, today))
                    .thenReturn(confirmedPrefetch("KO", confirmedDt));

            OverseasDividendBackfillFetch fetch =
                    service.fetchWindowForBackfill(ko, session, floor, today);
            BackfillWindowResult result = service.persistWindowForBackfill(fetch);

            assertThat(result.rowCount()).isEqualTo(1);
            assertThat(result.rawRowCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("EC: 빈 fetch → rowCount=0·rawRowCount=0, insertBatch 빈 목록")
        void emptyFetch_persist() throws InterruptedException {
            Stock ko = stock("KO");
            stubRightsByIce(List.of());
            when(dividendAmountPrefetcher.prefetchForBackfill(session, "KO", floor, today))
                    .thenReturn(emptyPrefetch());

            OverseasDividendBackfillFetch fetch =
                    service.fetchWindowForBackfill(ko, session, floor, today);
            BackfillWindowResult result = service.persistWindowForBackfill(fetch);

            assertThat(result.rowCount()).isZero();
            assertThat(result.rawRowCount()).isZero();
            assertThat(result.oldestTradeDate()).isNull();
            verify(corporateEventInserter).insertBatch(List.of());
        }
    }
}
