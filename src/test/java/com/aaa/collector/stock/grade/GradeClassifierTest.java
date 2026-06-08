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

@DisplayName("GradeClassifier 단위 테스트")
class GradeClassifierTest {

    private final GradeClassifier classifier = new GradeClassifier();

    private GradeInput stockInput(double listedYears, double percentile) {
        return new GradeInput("005930", "삼성전자", AssetType.STOCK, listedYears, percentile);
    }

    private GradeInput stockInput(String nameKo, double listedYears, double percentile) {
        return new GradeInput("005930", nameKo, AssetType.STOCK, listedYears, percentile);
    }

    private GradeInput etfInput(String nameKo, double listedYears, double percentile) {
        return new GradeInput("069500", nameKo, AssetType.ETF, listedYears, percentile);
    }

    @Nested
    @DisplayName("F 등급 — 우선 적용")
    class FGrade {

        @Test
        @DisplayName("시나리오 2: TDF 포함 종목명 — 상장 10년 + 상위 5%이더라도 F")
        void classify_tdfInName_returnsF() {
            GradeInput input = stockInput("TDF 2050", 10.0, 5.0);
            assertThat(classifier.classify(input)).isEqualTo(Grade.F);
        }

        @Test
        @DisplayName("시나리오 2: 액티브 포함 종목명 — 상장 10년 + 상위 5%이더라도 F")
        void classify_activeInName_returnsF() {
            GradeInput input = etfInput("KODEX 200 액티브", 10.0, 5.0);
            assertThat(classifier.classify(input)).isEqualTo(Grade.F);
        }

        @Test
        @DisplayName("TDF 포함 ETF — A 조건 충족해도 F 우선")
        void classify_tdfEtf_fTakesPriorityOverA() {
            GradeInput input = etfInput("삼성 TDF2050", 10.0, 5.0);
            assertThat(classifier.classify(input)).isEqualTo(Grade.F);
        }

        @Test
        @DisplayName("종목명에 TDF 없고 액티브 없는 일반 종목 — F 미적용")
        void classify_noTdfNoActive_notF() {
            GradeInput input = stockInput("삼성전자", 10.0, 5.0);
            assertThat(classifier.classify(input)).isNotEqualTo(Grade.F);
        }
    }

    @Nested
    @DisplayName("ETF 기본 C 등급 (REQ-007)")
    class EtfDefaultC {

        @Test
        @DisplayName("시나리오 4: 비-F ETF (상장 10년 + 상위 5%) — C 등급")
        void classify_nonFEtfLongListed_returnsC() {
            GradeInput input = etfInput("KODEX 200", 10.0, 5.0);
            assertThat(classifier.classify(input)).isEqualTo(Grade.C);
        }

        @Test
        @DisplayName("비-F ETF (상장 1년 + 하위 80%) — C 등급")
        void classify_nonFEtfShortListed_returnsC() {
            GradeInput input = etfInput("KODEX 코스닥150", 1.0, 80.0);
            assertThat(classifier.classify(input)).isEqualTo(Grade.C);
        }
    }

    @Nested
    @DisplayName("A 등급 — 상장 7년 이상 AND 상위 20%")
    class AGrade {

        @Test
        @DisplayName("시나리오 3: 상장 8년 + 상위 10% → A")
        void classify_8yearsTop10pct_returnsA() {
            assertThat(classifier.classify(stockInput(8.0, 10.0))).isEqualTo(Grade.A);
        }

        @Test
        @DisplayName("상장 7년 정확히 + 상위 20% 정확히 → A")
        void classify_exactly7yearsExactly20pct_returnsA() {
            assertThat(classifier.classify(stockInput(7.0, 20.0))).isEqualTo(Grade.A);
        }

        @Test
        @DisplayName("상장 10년 + 상위 1% → A")
        void classify_10yearsTop1pct_returnsA() {
            assertThat(classifier.classify(stockInput(10.0, 1.0))).isEqualTo(Grade.A);
        }
    }

    @Nested
    @DisplayName("B 등급 — 상장 3~7년 AND 상위 20%~60%")
    class BGrade {

        @Test
        @DisplayName("시나리오 3a: 상장 4년 + 상위 30% → B")
        void classify_4yearsTop30pct_returnsB() {
            assertThat(classifier.classify(stockInput(4.0, 30.0))).isEqualTo(Grade.B);
        }

        @Test
        @DisplayName("상장 3년 정확히 + 상위 20% 초과 60% 이하 → B")
        void classify_exactly3yearsTop40pct_returnsB() {
            assertThat(classifier.classify(stockInput(3.0, 40.0))).isEqualTo(Grade.B);
        }

        @Test
        @DisplayName("상장 6.9년 + 상위 59% → B")
        void classify_69yearsTop59pct_returnsB() {
            assertThat(classifier.classify(stockInput(6.9, 59.0))).isEqualTo(Grade.B);
        }
    }

    @Nested
    @DisplayName("C 등급 — 나머지 전부")
    class CGrade {

        @Test
        @DisplayName("시나리오 3: 8년이지만 하위 50% (백분위 50) → C (기간·백분위 AND 조건 미충족)")
        void classify_8yearsBottom50pct_returnsC() {
            assertThat(classifier.classify(stockInput(8.0, 50.0))).isEqualTo(Grade.C);
        }

        @Test
        @DisplayName("시나리오 3b: 7년 이상이지만 상위 20% 미충족 (50번째 백분위) → C (REQ-014)")
        void classify_7yearsNot20pct_returnsC() {
            assertThat(classifier.classify(stockInput(7.0, 50.0))).isEqualTo(Grade.C);
        }

        @Test
        @DisplayName("상장 2년 + 상위 5% → C (상장 기간 미충족)")
        void classify_2yearsTop5pct_returnsC() {
            assertThat(classifier.classify(stockInput(2.0, 5.0))).isEqualTo(Grade.C);
        }

        @Test
        @DisplayName("상장 5년 + 하위 50% (백분위 70) → C (A/B 모두 미충족)")
        void classify_5yearsBottom50pct_returnsC() {
            assertThat(classifier.classify(stockInput(5.0, 70.0))).isEqualTo(Grade.C);
        }

        @Test
        @DisplayName("상장 8년 + 상위 21% (백분위 21) → C (A는 상위 20% 미충족, B는 7년 이상이라 제외)")
        void classify_8yearsPercentile21_returnsC() {
            assertThat(classifier.classify(stockInput(8.0, 21.0))).isEqualTo(Grade.C);
        }
    }

    @Nested
    @DisplayName("시나리오 11 — REQ-013: 동시 분류 결정론성")
    class ConcurrentDeterminism {

        @Test
        @DisplayName("N개 Virtual Thread 동시 호출 결과 == 순차 호출 결과")
        @SuppressWarnings({
            "PMD.AvoidSynchronizedStatement", // 동시성 테스트 특성상 synchronized 불가피
            "PMD.AvoidCatchingGenericException", // Future.get()의
            // ExecutionException/InterruptedException 포착
            "PMD.AvoidThrowingRawExceptionTypes" // 테스트 실패 전파용
        })
        void classify_concurrentCalls_deterministicResults() throws InterruptedException {
            // Arrange
            List<GradeInput> inputs =
                    List.of(
                            stockInput("삼성전자", 8.0, 10.0), // A
                            stockInput("TDF2050", 10.0, 5.0), // F
                            etfInput("KODEX 200", 10.0, 5.0), // C (ETF)
                            stockInput("SK하이닉스", 4.0, 30.0), // B
                            stockInput("카카오", 2.0, 50.0) // C
                            );

            // 순차 실행 결과
            List<Grade> sequentialResults = inputs.stream().map(classifier::classify).toList();

            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            List<List<Grade>> concurrentResults = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                concurrentResults.add(new ArrayList<>());
            }

            // Act — Virtual Thread로 동시 실행
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
