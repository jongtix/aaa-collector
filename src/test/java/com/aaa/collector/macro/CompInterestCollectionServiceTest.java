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
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SPEC-COLLECTOR-KISGATE-001 M6(T16) — {@link CompInterestCollectionService} 게이트 경유 단위 테스트.
 *
 * <p>패턴 A는 Behavior:Changed이므로 신규 게이트 라우팅(throttle-on 4-arg 경유·단발 collect()당 세션 1회 open)과 기존 수집
 * 의미(malformed graceful skip·멱등·null 키 skip)를 함께 검증한다. 게이트의 매 시도 재경유·재시도는 {@code
 * GuardedKisExecutorTest}가 담당하므로 본 테스트는 응답 매핑·집계에 집중한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CompInterestCollectionService — 게이트 경유 단위 테스트")
class CompInterestCollectionServiceTest {

    private static final String TR_ID = "FHPST07020000";

    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private MacroIndicatorRepository macroIndicatorRepository;
    @Mock private LeaseSession session;

    private CompInterestCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new CompInterestCollectionService(
                        guardedKisExecutor, keyLeaseRegistry, macroIndicatorRepository);
        Mockito.lenient().when(keyLeaseRegistry.openSession()).thenReturn(session);
    }

    /** 게이트가 주어진 응답을 반환하도록 stub한다(throttle-on 4-arg 경유). */
    private void stubGate(KisCompInterestResponse response) throws InterruptedException {
        when(guardedKisExecutor.execute(
                        eq(session), any(), eq(TR_ID), eq(KisCompInterestResponse.class)))
                .thenReturn(response);
    }

    private KisCompInterestResponse response(List<KisCompInterestResponse.CompInterestRow> rows) {
        return new KisCompInterestResponse("0", "MCA00000", "정상처리", rows);
    }

    private KisCompInterestResponse.CompInterestRow cleanRow(
            String bcdtCode, String value, String date) {
        return new KisCompInterestResponse.CompInterestRow(
                bcdtCode, "CD(91일)", value, "2", "0.01", "-0.005", date);
    }

    /** malformed 행: hts_kor_isnm이 bcdt_code 패턴 */
    private KisCompInterestResponse.CompInterestRow malformedIsnmRow(String bcdtCode) {
        // hts_kor_isnm이 "Y0117" 같은 코드 → 필드 시프트 행
        return new KisCompInterestResponse.CompInterestRow(
                bcdtCode, "Y0117", "3.55", "2", "0.01", "-0.005", "20260613");
    }

    /** malformed 행: bond_mnrt_prpr이 비숫자 */
    private KisCompInterestResponse.CompInterestRow malformedValueRow(String bcdtCode) {
        return new KisCompInterestResponse.CompInterestRow(
                bcdtCode, "국고채30년", "N/A", "2", "0.01", "-0.005", "20260613");
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
            stubGate(response(List.of(cleanRow("Y0112", "3.72", "20260613"))));

            // Act
            service.collect();

            // Assert: per-batch 스냅샷 1회 + 게이트 throttle-on 4-arg 정확히 1회 경유
            verify(keyLeaseRegistry, times(1)).openSession();
            verify(guardedKisExecutor, times(1))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisCompInterestResponse.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 정상 수집
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 수집 — T0 실측 유효 8종")
    class HappyPath {

        private List<KisCompInterestResponse.CompInterestRow> eightCleanRows() {
            return List.of(
                    cleanRow("Y0112", "3.72", "20260613"),
                    cleanRow("Y0113", "3.80", "20260613"),
                    cleanRow("Y0114", "3.60", "20260613"),
                    cleanRow("Y0115", "102.50", "20260613"),
                    cleanRow("Y0116", "3.90", "20260613"),
                    cleanRow("Y0117", "3.95", "20260613"),
                    cleanRow("Y0198", "3.61", "20260613"),
                    cleanRow("Y0199", "3.74", "20260613"));
        }

        @Test
        @DisplayName("유효 8종 행 → 8건 시도·성공·skip=0, 8회 insertIgnoreDuplicate 호출")
        void stores8CleanRows_resultCounts() throws Exception {
            // Arrange
            stubGate(response(eightCleanRows()));

            // Act
            MacroCollectionResult result = service.collect();

            // Assert
            assertThat(result.attempted()).isEqualTo(8);
            assertThat(result.succeeded()).isEqualTo(8);
            assertThat(result.skipped()).isZero();
            verify(macroIndicatorRepository, times(8)).insertIgnoreDuplicate(any());
        }

        @Test
        @DisplayName("유효 8종 행 → indicator_code=KIS_RATE_{bcdt_code}, source=KIS, tradeDate 정확")
        void stores8CleanRows_withCorrectIndicatorCode() throws Exception {
            // Arrange
            stubGate(response(eightCleanRows()));

            // Act
            service.collect();

            // Assert
            ArgumentCaptor<MacroIndicator> captor = ArgumentCaptor.forClass(MacroIndicator.class);
            verify(macroIndicatorRepository, times(8)).insertIgnoreDuplicate(captor.capture());

            List<MacroIndicator> saved = captor.getAllValues();
            assertThat(saved)
                    .extracting(MacroIndicator::getIndicatorCode)
                    .containsExactlyInAnyOrder(
                            "KIS_RATE_Y0112",
                            "KIS_RATE_Y0113",
                            "KIS_RATE_Y0114",
                            "KIS_RATE_Y0115",
                            "KIS_RATE_Y0116",
                            "KIS_RATE_Y0117",
                            "KIS_RATE_Y0198",
                            "KIS_RATE_Y0199");
            assertThat(saved).allMatch(m -> m.getSource() == MacroSource.KIS);
            assertThat(saved).allMatch(m -> m.getTradeDate().equals(LocalDate.of(2026, 6, 13)));
        }

        @Test
        @DisplayName("value는 % 무정규화 — 3.72 그대로 저장")
        void storesValueWithoutNormalization() throws Exception {
            // Arrange
            stubGate(response(List.of(cleanRow("Y0112", "3.72", "20260613"))));

            // Act
            service.collect();

            // Assert
            ArgumentCaptor<MacroIndicator> captor = ArgumentCaptor.forClass(MacroIndicator.class);
            verify(macroIndicatorRepository).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getValue()).isEqualByComparingTo(new BigDecimal("3.72"));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // malformed 행 graceful skip
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("malformed 선두 행 graceful skip — REQ-BATCH3-031a/070")
    class MalformedSkip {

        @Test
        @DisplayName("hts_kor_isnm이 ^Y0\\d{3}$ 패턴인 행은 skip")
        void skipsRowWithCodePatternInIsnm() throws Exception {
            // Arrange — 8개 clean + 10개 malformed(Y0101,Y0103~Y0111)
            List<KisCompInterestResponse.CompInterestRow> rows =
                    List.of(
                            malformedIsnmRow("Y0101"),
                            malformedIsnmRow("Y0103"),
                            malformedIsnmRow("Y0104"),
                            malformedIsnmRow("Y0105"),
                            malformedIsnmRow("Y0106"),
                            malformedIsnmRow("Y0107"),
                            malformedIsnmRow("Y0108"),
                            malformedIsnmRow("Y0109"),
                            malformedIsnmRow("Y0110"),
                            malformedIsnmRow("Y0111"),
                            cleanRow("Y0112", "3.72", "20260613"),
                            cleanRow("Y0113", "3.80", "20260613"),
                            cleanRow("Y0114", "3.60", "20260613"),
                            cleanRow("Y0115", "102.50", "20260613"),
                            cleanRow("Y0116", "3.90", "20260613"),
                            cleanRow("Y0117", "3.95", "20260613"),
                            cleanRow("Y0198", "3.61", "20260613"),
                            cleanRow("Y0199", "3.74", "20260613"));
            stubGate(response(rows));

            // Act
            MacroCollectionResult result = service.collect();

            // Assert — 정확히 8개 저장, 10개 skip
            assertThat(result.succeeded()).isEqualTo(8);
            assertThat(result.skipped()).isEqualTo(10);
            verify(macroIndicatorRepository, times(8)).insertIgnoreDuplicate(any());
        }

        @Test
        @DisplayName("bond_mnrt_prpr이 비숫자인 행은 skip")
        void skipsRowWithNonNumericValue() throws Exception {
            // Arrange
            stubGate(
                    response(
                            List.of(
                                    malformedValueRow("Y0103"),
                                    cleanRow("Y0112", "3.72", "20260613"))));

            // Act
            MacroCollectionResult result = service.collect();

            // Assert
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 빈 output2
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("빈 output2 — 0건 성공 (REQ-BATCH3-073)")
    class EmptyOutput {

        @Test
        @DisplayName("빈 output2 → 저장 없음, 0건 성공")
        void emptyOutput2_zeroSuccess() throws Exception {
            stubGate(response(List.of()));

            MacroCollectionResult result = service.collect();

            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
            verify(macroIndicatorRepository, never()).insertIgnoreDuplicate(any());
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
            stubGate(response(List.of(cleanRow("Y0112", "3.72", "20260613"))));

            // Act — 2회 실행
            service.collect();
            service.collect();

            // Assert — 2번 insertIgnoreDuplicate 호출 (DB가 IGNORE 처리), 게이트도 2회·세션 2회 open
            verify(macroIndicatorRepository, times(2)).insertIgnoreDuplicate(any());
            verify(keyLeaseRegistry, times(2)).openSession();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // null 키 필드
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("null 키 필드 — skip")
    class NullKeyField {

        @Test
        @DisplayName("bcdtCode null 행 skip")
        void nullBcdtCode_skip() throws Exception {
            KisCompInterestResponse.CompInterestRow nullCode =
                    new KisCompInterestResponse.CompInterestRow(
                            null, "CD(91일)", "3.72", "2", "0.01", "-0.005", "20260613");
            stubGate(response(List.of(nullCode)));

            MacroCollectionResult result = service.collect();

            assertThat(result.skipped()).isEqualTo(1);
            verify(macroIndicatorRepository, never()).insertIgnoreDuplicate(any());
        }

        @Test
        @DisplayName("stckBsopDate null 행 skip")
        void nullDate_skip() throws Exception {
            KisCompInterestResponse.CompInterestRow nullDate =
                    new KisCompInterestResponse.CompInterestRow(
                            "Y0112", "CD(91일)", "3.72", "2", "0.01", "-0.005", null);
            stubGate(response(List.of(nullDate)));

            MacroCollectionResult result = service.collect();

            assertThat(result.skipped()).isEqualTo(1);
            verify(macroIndicatorRepository, never()).insertIgnoreDuplicate(any());
        }
    }
}
