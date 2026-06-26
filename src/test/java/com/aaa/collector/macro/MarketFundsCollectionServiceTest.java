package com.aaa.collector.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.macro.enums.MacroSource;
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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SPEC-COLLECTOR-KISGATE-001 M6(T17) — {@link MarketFundsCollectionService} 게이트 경유 단위 테스트.
 *
 * <p>패턴 A는 Behavior:Changed이므로 신규 게이트 라우팅(throttle-on 4-arg 경유·단발 collect()당 세션 1회 open)과 기존 수집
 * 의미(9지표 분해·원 정규화·지표별 graceful skip·멱등)를 함께 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketFundsCollectionService — 게이트 경유 단위 테스트")
class MarketFundsCollectionServiceTest {

    private static final String TR_ID = "FHKST649100C0";

    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private MacroIndicatorRepository macroIndicatorRepository;
    @Mock private MacroIndicatorInserter macroIndicatorInserter;
    @Mock private LeaseSession session;

    @Captor private ArgumentCaptor<List<MacroIndicator>> inserterCaptor;

    private MarketFundsCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new MarketFundsCollectionService(
                        guardedKisExecutor,
                        keyLeaseRegistry,
                        macroIndicatorRepository,
                        macroIndicatorInserter);
        Mockito.lenient().when(keyLeaseRegistry.openSession()).thenReturn(session);
    }

    /** 게이트가 주어진 응답을 반환하도록 stub한다(throttle-on 4-arg 경유). */
    private void stubGate(KisMarketFundsResponse response) throws InterruptedException {
        when(guardedKisExecutor.execute(
                        eq(session), any(), eq(TR_ID), eq(KisMarketFundsResponse.class)))
                .thenReturn(response);
    }

    private KisMarketFundsResponse response(List<KisMarketFundsResponse.MarketFundsRow> rows) {
        return new KisMarketFundsResponse("0", "MCA00000", "정상처리", rows);
    }

    private KisMarketFundsResponse.MarketFundsRow sampleRow(String bsopDate) {
        return new KisMarketFundsResponse.MarketFundsRow(
                bsopDate, "590000", // cust_dpmn_amt 억원
                "180000", // crdt_loan_rmnd
                "120000", // mmf_amt
                "1500", // uncl_amt
                "30000", // futs_tfam_amt
                "100000", // sttp_amt
                "80000", // mxtp_amt
                "60000", // bntp_amt
                "50000"); // secu_lend_amt
    }

    // ────────────────────────────────────────────────────────────────────
    // 신규 게이트 라우팅 (Behavior:Changed)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("게이트 경유 — 패턴 A 신규 동작 (REQ-KISGATE-001/006a)")
    class GateRouting {

        @Test
        @DisplayName("단발 collect() = 1 batch — 세션 1회 open 후 게이트를 throttle-on(4-arg)으로 1회 경유")
        void collect_routesThroughGateOnceWithOwnSession() throws Exception {
            // Arrange
            stubGate(response(List.of(sampleRow("20260613"))));

            // Act
            service.collect("20260613");

            // Assert
            verify(keyLeaseRegistry, times(1)).openSession();
            verify(guardedKisExecutor, times(1))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisMarketFundsResponse.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 정상 수집
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 수집 — 9개 지표 분해·원 정규화")
    class HappyPath {

        @Test
        @DisplayName("샘플 행 → 9건 시도·9건 성공, 9회 insertIgnoreDuplicate 호출")
        void storesSampleRow_9Indicators() throws Exception {
            // Arrange
            stubGate(response(List.of(sampleRow("20260613"))));

            // Act
            MacroCollectionResult result = service.collect("20260613");

            // Assert
            assertThat(result.attempted()).isEqualTo(9);
            assertThat(result.succeeded()).isEqualTo(9);
            assertThat(result.skipped()).isZero();
            verify(macroIndicatorInserter, times(9)).insertBatch(any());
        }

        @Test
        @DisplayName("indicator_code가 §6.3 MKTFUND_* 매핑을 따름")
        void indicatorCodesMatchMapping() throws Exception {
            // Arrange
            stubGate(response(List.of(sampleRow("20260613"))));

            // Act
            service.collect("20260613");

            // Assert
            verify(macroIndicatorInserter, times(9)).insertBatch(inserterCaptor.capture());

            List<MacroIndicator> saved =
                    inserterCaptor.getAllValues().stream().flatMap(List::stream).toList();
            assertThat(saved)
                    .extracting(MacroIndicator::getIndicatorCode)
                    .containsExactlyInAnyOrder(
                            "MKTFUND_CUST_DEPOSIT",
                            "MKTFUND_CREDIT_LOAN",
                            "MKTFUND_MMF",
                            "MKTFUND_UNCOLLECTED",
                            "MKTFUND_FUTURES_DEPOSIT",
                            "MKTFUND_EQUITY_TYPE",
                            "MKTFUND_MIXED_TYPE",
                            "MKTFUND_BOND_TYPE",
                            "MKTFUND_SECURED_LOAN");
        }

        @Test
        @DisplayName("source=KIS, tradeDate=bsopDate 파싱 정확")
        void sourceAndTradeDateCorrect() throws Exception {
            // Arrange
            stubGate(response(List.of(sampleRow("20260613"))));

            // Act
            service.collect("20260613");

            // Assert
            verify(macroIndicatorInserter, times(9)).insertBatch(inserterCaptor.capture());

            List<MacroIndicator> sourceAndDateSaved =
                    inserterCaptor.getAllValues().stream().flatMap(List::stream).toList();
            assertThat(sourceAndDateSaved).allMatch(m -> m.getSource() == MacroSource.KIS);
            assertThat(sourceAndDateSaved)
                    .allMatch(m -> m.getTradeDate().equals(LocalDate.of(2026, 6, 13)));
        }

        @Test
        @DisplayName("억원 → 원 정규화 (×EOK_WON_TO_WON=10^8): 590000억원 = 59조원 정확 저장")
        void wonNormalization_custDepositCorrect() throws Exception {
            // Arrange
            stubGate(response(List.of(sampleRow("20260613"))));

            // Act
            service.collect("20260613");

            // Assert
            verify(macroIndicatorInserter, times(9)).insertBatch(inserterCaptor.capture());

            MacroIndicator custDeposit =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .filter(m -> "MKTFUND_CUST_DEPOSIT".equals(m.getIndicatorCode()))
                            .findFirst()
                            .orElseThrow();

            // 590000억원 × 10^8 = 59,000,000,000,000 원 (59조원)
            BigDecimal expected =
                    new BigDecimal("590000")
                            .multiply(
                                    BigDecimal.valueOf(
                                            MarketFundsCollectionService.EOK_WON_TO_WON));
            assertThat(custDeposit.getValue()).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("대규모 값 (~10^14) 정밀도 손실 없이 저장 — DECIMAL(24,8) 수용 검증")
        void largeValueNoPrecisionLoss() throws Exception {
            // Arrange — 예탁금 1000000억원 = 10^14 원
            KisMarketFundsResponse.MarketFundsRow largeRow =
                    new KisMarketFundsResponse.MarketFundsRow(
                            "20260613",
                            "1000000", // 100조원 = 10^14
                            "1",
                            "1",
                            "1",
                            "1",
                            "1",
                            "1",
                            "1",
                            "1");
            stubGate(response(List.of(largeRow)));

            // Act
            service.collect("20260613");

            // Assert
            verify(macroIndicatorInserter, times(9)).insertBatch(inserterCaptor.capture());

            MacroIndicator custDeposit =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .filter(m -> "MKTFUND_CUST_DEPOSIT".equals(m.getIndicatorCode()))
                            .findFirst()
                            .orElseThrow();

            // 1000000억원 × 10^8 = 100,000,000,000,000 원 (10^14 — 14자리 정수부)
            BigDecimal expected =
                    new BigDecimal("1000000").multiply(BigDecimal.valueOf(100_000_000L));
            assertThat(custDeposit.getValue()).isEqualByComparingTo(expected);
            // DECIMAL(24,8): 정수부 16자리 내 — 14자리 수용 확인
            assertThat(expected.precision() - expected.scale()).isLessThanOrEqualTo(16);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 빈 output
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("빈 output — 0건 성공 (REQ-BATCH3-073)")
    class EmptyOutput {

        @Test
        @DisplayName("빈 output → 저장 없음, 0건 성공")
        void emptyOutput_zeroSuccess() throws Exception {
            stubGate(response(List.of()));

            MacroCollectionResult result = service.collect("20260613");

            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
            verify(macroIndicatorInserter, never()).insertBatch(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 검증 실패 skip
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("개별 지표 null·파싱 실패 skip (REQ-BATCH3-070)")
    class IndicatorSkip {

        @Test
        @DisplayName("특정 지표 값이 null이면 해당 지표만 skip, 나머지 저장")
        void nullIndicatorValue_skipsOnly1() throws Exception {
            // Arrange — custDpmnAmt null
            KisMarketFundsResponse.MarketFundsRow partialRow =
                    new KisMarketFundsResponse.MarketFundsRow(
                            "20260613",
                            null, // cust_dpmn_amt null → skip
                            "180000",
                            "120000",
                            "1500",
                            "30000",
                            "100000",
                            "80000",
                            "60000",
                            "50000");
            stubGate(response(List.of(partialRow)));

            // Act
            MacroCollectionResult result = service.collect("20260613");

            // Assert — 8건 성공, 1건 skip
            assertThat(result.succeeded()).isEqualTo(8);
            assertThat(result.skipped()).isEqualTo(1);
            verify(macroIndicatorInserter, times(8)).insertBatch(any());
        }

        @Test
        @DisplayName("비숫자 지표 값은 skip")
        void nonNumericValue_skip() throws Exception {
            // Arrange — mmfAmt = "N/A"
            KisMarketFundsResponse.MarketFundsRow badRow =
                    new KisMarketFundsResponse.MarketFundsRow(
                            "20260613",
                            "590000",
                            "180000",
                            "N/A",
                            "1500",
                            "30000",
                            "100000",
                            "80000",
                            "60000",
                            "50000");
            stubGate(response(List.of(badRow)));

            // Act
            MacroCollectionResult result = service.collect("20260613");

            // Assert
            assertThat(result.succeeded()).isEqualTo(8);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("bsopDate null → 해당 행 9건 전부 skip")
        void nullBsopDate_skips9() throws Exception {
            // Arrange
            KisMarketFundsResponse.MarketFundsRow nullDateRow =
                    new KisMarketFundsResponse.MarketFundsRow(
                            null, "590000", "180000", "120000", "1500", "30000", "100000", "80000",
                            "60000", "50000");
            stubGate(response(List.of(nullDateRow)));

            // Act
            MacroCollectionResult result = service.collect("20260613");

            // Assert — 9건 전부 skip
            assertThat(result.attempted()).isEqualTo(9);
            assertThat(result.skipped()).isEqualTo(9);
            verify(macroIndicatorInserter, never()).insertBatch(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 멱등성
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("멱등 저장 — uk_macro_indicators")
    class Idempotency {

        @Test
        @DisplayName("동일 행 재수집 시 insertIgnoreDuplicate 재호출 (DB가 중복 무시)")
        void idempotentRerun_insertIgnoreCalledTwice() throws Exception {
            stubGate(response(List.of(sampleRow("20260613"))));

            // Act — 2회 실행
            service.collect("20260613");
            service.collect("20260613");

            // Assert — 2회 × 9건 = 18회 호출, 게이트도 2회·세션 2회 open
            verify(macroIndicatorInserter, times(18)).insertBatch(any());
            verify(keyLeaseRegistry, times(2)).openSession();
        }
    }
}
