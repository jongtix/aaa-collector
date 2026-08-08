package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.CorporateEventInserter;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OverseasDividendBackfillService} 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001 코드리뷰
 * W-1).
 *
 * <p>{@code rights-by-ice} 청크 절단 의심 임계({@link
 * OverseasDividendBackfillService#BACKFILL_RIGHTS_TRUNCATION_THRESHOLD_ROWS}) 경계값 동작을 mock {@link
 * OverseasRightsCollectionService}로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasDividendBackfillService 단위 테스트")
class OverseasDividendBackfillServiceTest {

    private static final int THRESHOLD = 40;

    @Mock private OverseasRightsCollectionService rightsCollectionService;
    @Mock private DividendAmountPrefetcher dividendAmountPrefetcher;
    @Mock private CorporateEventInserter corporateEventInserter;
    @Mock private LeaseSession session;

    private OverseasDividendBackfillService service;

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

    /** {@code output1} 원본 행 N개짜리 응답 — caTitle=null(비현금배당 skip 경로, W-1 임계 검사는 필터링 이전 원본 행수 기준). */
    private KisOverseasRightsResponse responseWithRows(int rowCount) {
        List<KisOverseasRightsResponse.RightsRow> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            rows.add(
                    new KisOverseasRightsResponse.RightsRow(
                            null, null, null, null, null, null, null, null, null, null, null,
                            null));
        }
        return new KisOverseasRightsResponse("0", "0000", "OK", rows);
    }

    @Test
    @DisplayName("청크 원본 행수가 임계 이상이면 절단 의심으로 fail-closed 한다(W-1)")
    void fetchWindowForBackfill_throwsWhenChunkRowsReachThreshold() throws InterruptedException {
        Stock stock = stock("O");
        LocalDate from = LocalDate.of(2020, 1, 1);
        LocalDate to = LocalDate.of(2021, 1, 1); // 24개월 폭 미만 — 단일 청크

        when(rightsCollectionService.fetch(
                        any(LeaseSession.class),
                        anyString(),
                        any(LocalDate.class),
                        any(LocalDate.class)))
                .thenReturn(responseWithRows(THRESHOLD));

        assertThatThrownBy(() -> service.fetchWindowForBackfill(stock, session, from, to))
                .isInstanceOf(OverseasDividendBackfillPrefetchFailedException.class)
                .hasMessageContaining("절단 의심");
    }

    @Test
    @DisplayName("청크 원본 행수가 임계 미만이면 정상 처리한다(경계값, W-1)")
    void fetchWindowForBackfill_doesNotThrowWhenChunkRowsBelowThreshold()
            throws InterruptedException {
        Stock stock = stock("O");
        LocalDate from = LocalDate.of(2020, 1, 1);
        LocalDate to = LocalDate.of(2021, 1, 1);

        when(rightsCollectionService.fetch(
                        any(LeaseSession.class),
                        anyString(),
                        any(LocalDate.class),
                        any(LocalDate.class)))
                .thenReturn(responseWithRows(THRESHOLD - 1));
        when(dividendAmountPrefetcher.prefetchForBackfill(
                        any(LeaseSession.class),
                        anyString(),
                        any(LocalDate.class),
                        any(LocalDate.class)))
                .thenReturn(new DividendAmountPrefetch(Map.of(), Set.of(), 0, 0));

        OverseasDividendBackfillFetch fetch =
                service.fetchWindowForBackfill(stock, session, from, to);

        assertThat(fetch.rawRowCount()).isEqualTo(THRESHOLD - 1);
        assertThat(fetch.validRows()).isEmpty(); // caTitle=null → 전부 비현금배당 skip
    }

    @Test
    @DisplayName("청크 원본 행수가 0이면 정상 처리한다(경계값, W-1)")
    void fetchWindowForBackfill_doesNotThrowWhenChunkRowsIsZero() throws InterruptedException {
        Stock stock = stock("O");
        LocalDate from = LocalDate.of(2020, 1, 1);
        LocalDate to = LocalDate.of(2021, 1, 1);

        when(rightsCollectionService.fetch(
                        any(LeaseSession.class),
                        anyString(),
                        any(LocalDate.class),
                        any(LocalDate.class)))
                .thenReturn(responseWithRows(0));
        when(dividendAmountPrefetcher.prefetchForBackfill(
                        any(LeaseSession.class),
                        anyString(),
                        any(LocalDate.class),
                        any(LocalDate.class)))
                .thenReturn(new DividendAmountPrefetch(Map.of(), Set.of(), 0, 0));

        OverseasDividendBackfillFetch fetch =
                service.fetchWindowForBackfill(stock, session, from, to);

        assertThat(fetch.rawRowCount()).isZero();
        assertThat(fetch.validRows()).isEmpty();
    }
}
