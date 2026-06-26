package com.aaa.collector.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.daily.WarningCountingOhlcvInserter;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SPEC-COLLECTOR-KISGATE-001 M6(T18) — {@link SectorIndexCollectionService} 게이트 경유 단위 테스트.
 *
 * <p>패턴 A는 Behavior:Changed이므로 신규 게이트 라우팅(throttle-on 4-arg 경유·종목 순회 = 1 batch이므로 세션 1회 open)과 기존
 * 수집 의미(U 전용 API·INDEX 부재 skip·종목별 graceful skip·종가≤0·멱등)를 함께 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SectorIndexCollectionService — 게이트 경유 단위 테스트")
class SectorIndexCollectionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);
    private static final String TR_ID = "FHKUP03500100";

    @Mock private StockRepository stockRepository;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private LeaseSession session;
    @Mock private WarningCountingOhlcvInserter ohlcvInserter;

    private SectorIndexCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new SectorIndexCollectionService(
                        stockRepository, guardedKisExecutor, keyLeaseRegistry, ohlcvInserter);
        Mockito.lenient().when(keyLeaseRegistry.openSession()).thenReturn(session);
    }

    private void stubGate(KisSectorIndexResponse response) throws InterruptedException {
        when(guardedKisExecutor.execute(
                        eq(session), any(), eq(TR_ID), eq(KisSectorIndexResponse.class)))
                .thenReturn(response);
    }

    private Stock indexStock(String symbol, long id) {
        Stock s =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("지수_" + symbol)
                        .market(Market.KRX)
                        .assetType(AssetType.INDEX)
                        .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private KisSectorIndexResponse successResponse(
            List<KisSectorIndexResponse.SectorIndexRow> rows) {
        return new KisSectorIndexResponse("0", "MCA00000", "정상처리", rows);
    }

    private KisSectorIndexResponse.SectorIndexRow row(String date, String close) {
        return new KisSectorIndexResponse.SectorIndexRow(
                date, "2700.00", "2720.00", "2690.00", close, "1000000", "50000000000");
    }

    // ────────────────────────────────────────────────────────────────────
    // 정상 매핑 + 게이트 라우팅
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("게이트 경유 — 패턴 A 신규 동작 (REQ-KISGATE-001/006a)")
    class GateRouting {

        @Test
        @DisplayName("종목 순회 = 1 batch — 세션 1회 open, 게이트 U-API 2회 경유(동일 세션 공유)")
        void collect_oneSession_twoGateCalls() throws Exception {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            Stock kosdaq = indexStock("1001", 2L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi, kosdaq));
            stubGate(successResponse(List.of(row("20260613", "2715.35"))));

            // Act
            service.collect(TODAY);

            // Assert: per-batch 스냅샷 1회 + U-API 2회 경유(KOSPI/KOSDAQ), 동일 세션 공유
            verify(keyLeaseRegistry, times(1)).openSession();
            verify(guardedKisExecutor, times(2))
                    .execute(
                            eq(session),
                            argThat(
                                    fn -> {
                                        java.net.URI uri =
                                                fn.apply(
                                                        org.springframework.web.util
                                                                .UriComponentsBuilder
                                                                .newInstance());
                                        return uri.toString().contains("FID_COND_MRKT_DIV_CODE=U");
                                    }),
                            eq(TR_ID),
                            eq(KisSectorIndexResponse.class));
        }
    }

    @Nested
    @DisplayName("정상 수집 — 매핑 검증")
    class HappyPath {

        @Test
        @DisplayName("KOSPI·KOSDAQ 2종 조회 시 daily_ohlcv에 저장")
        void collectsBothSymbols_storedToOhlcv() throws Exception {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            Stock kosdaq = indexStock("1001", 2L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi, kosdaq));
            stubGate(successResponse(List.of(row("20260613", "2715.35"))));

            // Act
            SectorIndexCollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isZero();

            // insertBatch 2회 호출 (각 종목 1행 배치, REQ-INSERT-006 AC-3)
            verify(ohlcvInserter, times(2)).insertBatch(any(), any());
        }

        @Test
        @DisplayName("J-API는 절대 호출하지 않는다 — REQ-BATCH3-021b")
        void neverCallsJApi() throws Exception {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi));
            when(guardedKisExecutor.execute(
                            eq(session), any(), anyString(), eq(KisSectorIndexResponse.class)))
                    .thenReturn(successResponse(List.of(row("20260613", "2715.35"))));

            // Act
            service.collect(TODAY);

            // Assert — J API path가 포함된 URI는 호출되지 않아야 함
            verify(guardedKisExecutor, never())
                    .execute(
                            eq(session),
                            argThat(
                                    fn -> {
                                        java.net.URI uri =
                                                fn.apply(
                                                        org.springframework.web.util
                                                                .UriComponentsBuilder
                                                                .newInstance());
                                        return uri.toString().contains("FHKST03010100")
                                                || uri.toString()
                                                        .contains("FID_COND_MRKT_DIV_CODE=J");
                                    }),
                            anyString(),
                            any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 검증 실패 건별 skip
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("검증 실패 — 건별 skip")
    class ValidationSkip {

        @Test
        @DisplayName("종가 ≤ 0인 행은 저장하지 않는다")
        void skipRowWithNonPositiveClose() throws Exception {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi));
            KisSectorIndexResponse.SectorIndexRow badRow =
                    new KisSectorIndexResponse.SectorIndexRow(
                            "20260613", "2700.00", "2720.00", "2690.00", "0", "100", "500");
            stubGate(successResponse(List.of(badRow)));

            // Act
            service.collect(TODAY);

            // Assert — 저장 없음
            verify(ohlcvInserter, never()).insertBatch(any(), any());
        }

        @Test
        @DisplayName("null 날짜 필드 행은 저장하지 않는다")
        void skipRowWithNullDate() throws Exception {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi));
            KisSectorIndexResponse.SectorIndexRow badRow =
                    new KisSectorIndexResponse.SectorIndexRow(
                            null, "2700.00", "2720.00", "2690.00", "2715.35", "100", "500");
            stubGate(successResponse(List.of(badRow)));

            // Act
            service.collect(TODAY);

            // Assert
            verify(ohlcvInserter, never()).insertBatch(any(), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 빈 output2 → 0건 성공
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("빈 응답 — 0건 성공 (REQ-BATCH3-073)")
    class EmptyResponse {

        @Test
        @DisplayName("빈 output2 → succeeded=2 (양쪽 API 호출 성공), 저장 0건")
        void emptyOutput2_countedAsSuccess() throws Exception {
            // Arrange — KOSPI·KOSDAQ 양쪽 등록, 빈 응답
            Stock kospi = indexStock("0001", 1L);
            Stock kosdaq = indexStock("1001", 2L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi, kosdaq));
            stubGate(successResponse(List.of()));

            // Act
            SectorIndexCollectionResult result = service.collect(TODAY);

            // Assert — API 호출은 성공(예외 없음) → succeeded 집계, 저장은 0건
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isZero();
            verify(ohlcvInserter, never()).insertBatch(any(), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // INDEX 행 부재 → skip
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("INDEX 행 부재 — skip (REQ-BATCH3-021)")
    class IndexRowAbsent {

        @Test
        @DisplayName("대상 INDEX 행 부재 시 skip하고 게이트 호출 없음")
        void absentIndexRow_skipWithoutApiCall() throws Exception {
            // Arrange — DB에 INDEX 행이 없음
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of());

            // Act
            SectorIndexCollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(2);
            assertThat(result.succeeded()).isZero();
            verify(guardedKisExecutor, never()).execute(any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("KOSDAQ 행만 부재 시 KOSPI만 수집")
        void onlyKosdaqAbsent_kosPiStillCollected() throws Exception {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi)); // KOSDAQ 없음
            stubGate(successResponse(List.of(row("20260613", "2715.35"))));

            // Act
            SectorIndexCollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 종목별 graceful skip — 게이트 예외 흡수 (REQ-KISGATE-022)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("종목별 graceful skip — 게이트 예외 흡수 (패턴 A 종단 = 종목 skip)")
    class PerSymbolGracefulSkip {

        @Test
        @DisplayName("한 종목 게이트 소진(EGW00201) — 해당 종목만 skip, 나머지 수집 계속")
        void gateExhausted_skipsThatSymbol_continuesOthers() throws Exception {
            // Arrange — KOSPI는 소진 예외, KOSDAQ는 정상
            Stock kospi = indexStock("0001", 1L);
            Stock kosdaq = indexStock("1001", 2L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi, kosdaq));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisSectorIndexResponse.class)))
                    .thenThrow(new com.aaa.collector.kis.KisRateLimitException("test", "EGW00201"))
                    .thenReturn(successResponse(List.of(row("20260613", "2715.35"))));

            // Act
            SectorIndexCollectionResult result = service.collect(TODAY);

            // Assert — 소진 종목은 graceful skip, 나머지는 성공 (전파 아님)
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 멱등성
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("멱등 저장 — uk_daily_ohlcv")
    class Idempotency {

        @Test
        @DisplayName("동일 행 재수집 시 insertBatch 재호출 (DB가 중복 무시)")
        void idempotentSave_insertIgnoreCalledAgain() throws Exception {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi));
            stubGate(successResponse(List.of(row("20260613", "2715.35"))));

            // Act — 2회 실행
            service.collect(TODAY);
            service.collect(TODAY);

            // Assert — insertBatch 총 2회 (각 실행 1회씩, DB가 중복 무시함), 세션도 2회 open
            verify(ohlcvInserter, times(2)).insertBatch(any(), any());
            verify(keyLeaseRegistry, times(2)).openSession();
        }
    }
}
