package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GradeClassifier 단위 테스트 — 데이터 게이트 + ADTV 절대 임계값 모델")
class GradeClassifierTest {

    private final GradeClassifier classifier = new GradeClassifier();

    /** KRX 일반 주식 입력 헬퍼 */
    private GradeInput krxStockInput(long holdingDays, double adtv) {
        return new GradeInput("005930", "삼성전자", AssetType.STOCK, holdingDays, adtv, "KRX");
    }

    private GradeInput krxStockInput(String nameKo, long holdingDays, double adtv) {
        return new GradeInput("005930", nameKo, AssetType.STOCK, holdingDays, adtv, "KRX");
    }

    private GradeInput krxEtfInput(String nameKo, long holdingDays, double adtv) {
        return new GradeInput("069500", nameKo, AssetType.ETF, holdingDays, adtv, "KRX");
    }

    private GradeInput usStockInput(long holdingDays, double adtv) {
        return new GradeInput("AAPL", "Apple Inc.", AssetType.STOCK, holdingDays, adtv, "US");
    }

    // -----------------------------------------------------------------------
    // AC-1: F 등급 (우선 순위 1위)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-1 — F 등급: TDF 또는 액티브 포함 (최우선)")
    class FGrade {

        @Test
        @DisplayName("TDF 포함 종목명 — holdingDays 750 이상 + ADTV HIGH 이상이어도 F")
        void classify_tdfInName_returnsF() {
            GradeInput input = krxStockInput("TDF 2050", 1000L, 6e10);
            assertThat(classifier.classify(input)).isEqualTo(Grade.F);
        }

        @Test
        @DisplayName("액티브 포함 ETF — holdingDays 750 이상 + ADTV HIGH 이상이어도 F")
        void classify_activeInName_returnsF() {
            GradeInput input = krxEtfInput("KODEX 200 액티브", 1000L, 6e10);
            assertThat(classifier.classify(input)).isEqualTo(Grade.F);
        }

        @Test
        @DisplayName("TDF 포함 ETF — F가 ETF-C보다 우선")
        void classify_tdfEtf_fTakesPriorityOverEtfC() {
            GradeInput input = krxEtfInput("삼성 TDF2050", 800L, 6e10);
            assertThat(classifier.classify(input)).isEqualTo(Grade.F);
        }

        @Test
        @DisplayName("TDF 없고 액티브 없는 종목 — F 미적용")
        void classify_noTdfNoActive_notF() {
            GradeInput input = krxStockInput("삼성전자", 1000L, 6e10);
            assertThat(classifier.classify(input)).isNotEqualTo(Grade.F);
        }

        @Test
        @DisplayName("nameKo가 null — F 미적용 (NPE 없음)")
        void classify_nullNameKo_notF() {
            GradeInput input = new GradeInput("005930", null, AssetType.STOCK, 1000L, 6e10, "KRX");
            assertThat(classifier.classify(input)).isNotEqualTo(Grade.F);
        }
    }

    // -----------------------------------------------------------------------
    // AC-2: 비대표 ETF → C
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-2 — 비-F ETF: 강제 C")
    class EtfDefaultC {

        @Test
        @DisplayName("비-F ETF (holdingDays 1000 + ADTV HIGH 초과) — C 등급")
        void classify_nonFEtfHighAdtv_returnsC() {
            GradeInput input = krxEtfInput("KODEX 200", 1000L, 6e10);
            assertThat(classifier.classify(input)).isEqualTo(Grade.C);
        }

        @Test
        @DisplayName("비-F ETF (holdingDays 적음 + ADTV 낮음) — C 등급")
        void classify_nonFEtfLowAdtv_returnsC() {
            GradeInput input = krxEtfInput("KODEX 코스닥150", 100L, 1e8);
            assertThat(classifier.classify(input)).isEqualTo(Grade.C);
        }
    }

    // -----------------------------------------------------------------------
    // AC-3: A 등급 — holdingDays ≥ 750 AND ADTV ≥ HIGH
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-3 — A 등급: holdingDays ≥ 750 AND ADTV ≥ HIGH")
    class AGrade {

        @Test
        @DisplayName("KRX: holdingDays=750 정확히 + ADTV=5e10 정확히 → A (경계값)")
        void classify_krx_exactly750Days_exactly5e10_returnsA() {
            assertThat(classifier.classify(krxStockInput(750L, 5e10))).isEqualTo(Grade.A);
        }

        @Test
        @DisplayName("KRX: holdingDays=1000 + ADTV=6e10 → A")
        void classify_krx_1000Days_6e10_returnsA() {
            assertThat(classifier.classify(krxStockInput(1000L, 6e10))).isEqualTo(Grade.A);
        }

        @Test
        @DisplayName("US: holdingDays=750 정확히 + ADTV=2e9 정확히 → A (경계값)")
        void classify_us_exactly750Days_exactly2e9_returnsA() {
            assertThat(classifier.classify(usStockInput(750L, 2e9))).isEqualTo(Grade.A);
        }

        @Test
        @DisplayName("US: holdingDays=900 + ADTV=3e9 → A")
        void classify_us_900Days_3e9_returnsA() {
            assertThat(classifier.classify(usStockInput(900L, 3e9))).isEqualTo(Grade.A);
        }
    }

    // -----------------------------------------------------------------------
    // AC-4: B 등급 — holdingDays ≥ 250 AND LOW ≤ ADTV < HIGH
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-4 — B 등급: holdingDays ≥ 250 AND LOW ≤ ADTV < HIGH")
    class BGrade {

        @Test
        @DisplayName("KRX: holdingDays=250 정확히 + ADTV=1e10 정확히 → B (경계값)")
        void classify_krx_exactly250Days_exactly1e10_returnsB() {
            assertThat(classifier.classify(krxStockInput(250L, 1e10))).isEqualTo(Grade.B);
        }

        @Test
        @DisplayName("KRX: holdingDays=500 + ADTV=2e10 (LOW 이상 HIGH 미만) → B")
        void classify_krx_500Days_2e10_returnsB() {
            assertThat(classifier.classify(krxStockInput(500L, 2e10))).isEqualTo(Grade.B);
        }

        @Test
        @DisplayName(
                "KRX: holdingDays=750 + ADTV=HIGH 미만(4.9e10) → B (holdingDays ≥ 750이지만 ADTV < HIGH)")
        void classify_krx_750Days_adtvBelowHigh_returnsB() {
            assertThat(classifier.classify(krxStockInput(750L, 4.9e10))).isEqualTo(Grade.B);
        }

        @Test
        @DisplayName("US: holdingDays=250 정확히 + ADTV=5e8 정확히 → B (경계값)")
        void classify_us_exactly250Days_exactly5e8_returnsB() {
            assertThat(classifier.classify(usStockInput(250L, 5e8))).isEqualTo(Grade.B);
        }

        @Test
        @DisplayName("US: holdingDays=600 + ADTV=1e9 (LOW 이상 HIGH 미만) → B")
        void classify_us_600Days_1e9_returnsB() {
            assertThat(classifier.classify(usStockInput(600L, 1e9))).isEqualTo(Grade.B);
        }
    }

    // -----------------------------------------------------------------------
    // AC-5: C 등급 — 나머지
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-5 — C 등급: 나머지 전부")
    class CGrade {

        @Test
        @DisplayName("KRX: holdingDays=749 (A/B 기간 미충족) + ADTV=HIGH → C")
        void classify_krx_749Days_highAdtv_returnsC() {
            assertThat(classifier.classify(krxStockInput(749L, 6e10))).isEqualTo(Grade.C);
        }

        @Test
        @DisplayName("KRX: holdingDays=249 + ADTV=LOW → C (holdingDays B 기준 미충족)")
        void classify_krx_249Days_lowAdtv_returnsC() {
            assertThat(classifier.classify(krxStockInput(249L, 1e10))).isEqualTo(Grade.C);
        }

        @Test
        @DisplayName("KRX: holdingDays=250 + ADTV=LOW 미만(9.9e9) → C (B 하한 미달)")
        void classify_krx_250Days_adtvBelowLow_returnsC() {
            assertThat(classifier.classify(krxStockInput(250L, 9.9e9))).isEqualTo(Grade.C);
        }

        @Test
        @DisplayName("KRX: holdingDays=249 + ADTV=HIGH → C (holdingDays B 기준 미충족)")
        void classify_krx_249Days_highAdtv_returnsC() {
            assertThat(classifier.classify(krxStockInput(249L, 6e10))).isEqualTo(Grade.C);
        }

        @Test
        @DisplayName("KRX: holdingDays=0 (일봉 없음) → C")
        void classify_krx_0Days_returnsC() {
            assertThat(classifier.classify(krxStockInput(0L, 0.0))).isEqualTo(Grade.C);
        }
    }

    // -----------------------------------------------------------------------
    // AC-6: 경계값 정확성 검증
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-6 — 경계값 정밀 검증")
    class BoundaryValues {

        @Test
        @DisplayName("KRX: holdingDays=750, ADTV=5e10 → A (둘 다 경계값 정확히)")
        void classify_krx_a_exactBoundary() {
            assertThat(classifier.classify(krxStockInput(750L, 5e10))).isEqualTo(Grade.A);
        }

        @Test
        @DisplayName("KRX: holdingDays=750, ADTV=5e10 미만 1원 차이 → B")
        void classify_krx_adtvJustBelowHigh_returnsB() {
            // ADTV가 HIGH보다 0.01 낮으면 B (holdingDays ≥ 250 AND LOW ≤ ADTV < HIGH)
            assertThat(classifier.classify(krxStockInput(750L, 5e10 - 1))).isEqualTo(Grade.B);
        }

        @Test
        @DisplayName("KRX: holdingDays=250, ADTV=1e10 → B (둘 다 B 경계값 정확히)")
        void classify_krx_b_exactBoundary() {
            assertThat(classifier.classify(krxStockInput(250L, 1e10))).isEqualTo(Grade.B);
        }

        @Test
        @DisplayName("KRX: holdingDays=250, ADTV=1e10 미만 → C (B 하한 미달)")
        void classify_krx_adtvJustBelowLow_returnsC() {
            assertThat(classifier.classify(krxStockInput(250L, 1e10 - 1))).isEqualTo(Grade.C);
        }
    }

    // -----------------------------------------------------------------------
    // AC-12: 동시 분류 결정론성 (REQ-013)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-12 — REQ-013: 동시 분류 결정론성")
    class ConcurrentDeterminism {

        @Test
        @DisplayName("N개 Virtual Thread 동시 호출 결과 == 순차 호출 결과")
        @SuppressWarnings({
            "PMD.AvoidSynchronizedStatement",
            "PMD.AvoidCatchingGenericException",
            "PMD.AvoidThrowingRawExceptionTypes"
        })
        void classify_concurrentCalls_deterministicResults() throws InterruptedException {
            // Arrange
            List<GradeInput> inputs =
                    List.of(
                            krxStockInput("삼성전자", 1000L, 6e10), // A
                            krxStockInput("TDF2050", 1000L, 6e10), // F
                            krxEtfInput("KODEX 200", 1000L, 6e10), // C (ETF)
                            krxStockInput("SK하이닉스", 500L, 2e10), // B
                            krxStockInput("카카오", 100L, 5e8) // C
                            );

            List<Grade> sequentialResults = inputs.stream().map(classifier::classify).toList();

            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            List<List<Grade>> concurrentResults = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                concurrentResults.add(new ArrayList<>());
            }

            // Act
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threadCount; t++) {
                    final int idx = t;
                    futures.add(
                            executor.submit(
                                    () -> {
                                        try {
                                            startLatch.await();
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        List<Grade> results =
                                                inputs.stream().map(classifier::classify).toList();
                                        synchronized (concurrentResults) {
                                            concurrentResults.set(idx, results);
                                        }
                                    }));
                }
                startLatch.countDown();
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            // Assert
            for (List<Grade> result : concurrentResults) {
                assertThat(result).isEqualTo(sequentialResults);
            }
        }
    }
}
