package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.ranking.KisDomesticRankingClient;
import com.aaa.collector.kis.ranking.KisDomesticRankingResponse;
import com.aaa.collector.kis.ranking.KisOverseasRankingClient;
import com.aaa.collector.kis.ranking.KisOverseasRankingResponse;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("GradeClassificationService 단위 테스트")
class GradeClassificationServiceTest {

    @Mock private StockRepository stockRepository;
    @Mock private KisDomesticRankingClient domesticRankingClient;
    @Mock private KisOverseasRankingClient overseasRankingClient;
    @Mock private AdtvPercentileCalculator percentileCalculator;
    @Mock private GradeClassifier gradeClassifier;
    @Mock private StockGradePersistService stockGradePersistService;
    @Mock private ListedYearsResolver listedYearsResolver;

    @InjectMocks private GradeClassificationService service;

    private Stock buildStock(String symbol, Market market, LocalDate listedDate) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트_" + symbol)
                        .market(market)
                        .assetType(AssetType.STOCK)
                        .listedDate(listedDate)
                        .build();
        ReflectionTestUtils.setField(stock, "id", 1L);
        return stock;
    }

    @BeforeEach
    void setUpDefaults() {
        // 기본: 비어있지 않은 순위 응답(withhold 미발동) + 빈 백분위 계산 (lenient — 일부 테스트에서 미사용)
        // 분류 보류 시나리오(시나리오 4/5/7) 테스트는 각 테스트 내에서 빈/throw로 재스텁한다.
        lenient()
                .when(domesticRankingClient.fetchRanking())
                .thenReturn(
                        List.of(new KisDomesticRankingResponse.RankedStock("DUMMY", "1", "더미")));
        lenient()
                .when(overseasRankingClient.fetchRanking())
                .thenReturn(List.of(new KisOverseasRankingResponse.RankedStock("DUMMY", "1")));
        lenient().when(percentileCalculator.calculate(anyList())).thenReturn(Map.of());
        lenient().when(gradeClassifier.classify(any())).thenReturn(Grade.C);
        // 기본 listedYearsResolver: 상장일 있는 종목은 실제 날짜 기반 계산을 단순화하여 8.0 반환
        lenient().when(listedYearsResolver.resolve(any())).thenReturn(8.0);
    }

    @Nested
    @DisplayName("시나리오 7 — 상장폐지 종목 제외")
    class ActiveStocksOnly {

        @Test
        @DisplayName("findAllActive()가 반환한 종목만 분류 대상")
        void classify_usesOnlyActiveStocks() {
            Stock active = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(active));

            service.classify();

            verify(stockRepository).findAllActive();
            verify(gradeClassifier).classify(any());
        }

        @Test
        @DisplayName("활성 종목 없음 — 분류기 호출 없음")
        void classify_noActiveStocks_nothingClassified() {
            when(stockRepository.findAllActive()).thenReturn(List.of());

            service.classify();

            verify(gradeClassifier, never()).classify(any());
        }
    }

    @Nested
    @DisplayName("시나리오 8 — 시장별 데이터 소스 라우팅")
    class MarketRouting {

        @Test
        @DisplayName("KRX 종목 존재 시 — KisDomesticRankingClient 호출")
        void classify_kospiStock_callsDomesticClient() {
            Stock stock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));

            service.classify();

            verify(domesticRankingClient).fetchRanking();
        }

        @Test
        @DisplayName("US 종목 존재 시 — KisOverseasRankingClient 호출")
        void classify_nyseStock_callsOverseasClient() {
            Stock stock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));

            service.classify();

            verify(overseasRankingClient).fetchRanking();
        }
    }

    @Nested
    @DisplayName("시나리오 9 — 등급 산정 후 영속화 위임")
    class PersistenceDelegation {

        @Test
        @DisplayName("등급 산정 후 StockGradePersistService.persistSingle() 호출")
        void classify_gradeAssigned_delegatesToPersistService() {
            Stock stock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            service.classify();

            verify(stockGradePersistService)
                    .persistSingle(eq(stock), eq(Grade.A), any(ZonedDateTime.class));
        }
    }

    @Nested
    @DisplayName("시나리오 5/6 — 분류 실패 격리")
    class FailureHandling {

        @Test
        @DisplayName("한 종목 분류 실패 — 나머지 종목 persistSingle() 계속 호출")
        void classify_failureOnOneStock_continuesOthers() {
            Stock fail = buildStock("FAIL", Market.KOSPI, LocalDate.of(2010, 1, 1));
            Stock ok = buildStock("OK", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(fail, ok));
            when(gradeClassifier.classify(any()))
                    .thenThrow(new RuntimeException("분류 실패"))
                    .thenReturn(Grade.B);

            service.classify();

            // "OK" 종목은 정상 처리 — persistSingle 1회 호출
            verify(stockGradePersistService).persistSingle(eq(ok), eq(Grade.B), any());
        }

        @Test
        @DisplayName("시나리오 6: 분류 실패 종목 — persistSingle 호출 없음")
        void classify_failureStock_noPersistCall() {
            Stock stock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(gradeClassifier.classify(any())).thenThrow(new RuntimeException("분류 실패"));

            service.classify();

            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("시나리오 4/5/7 — 순위 데이터 결손 분류 보류 (SPEC-COLLECTOR-GRADE-002 REQ-004)")
    class RankingDeficitWithhold {

        @Test
        @DisplayName("시나리오 4: warm + KRX fetchRanking 빈 결과 — KRX 종목 persistSingle 미호출(보류)")
        void classify_warmKrxRankingEmpty_krxStockNotPersisted() {
            // Arrange: 이전 등급 행이 존재하는 warm 상태, KRX 순위 빈 결과
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of()); // 빈 결과 = 시장 결손

            // Act
            service.classify();

            // Assert: persistSingle 미호출 — 이전 등급 유지(보류)
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("시나리오 5: US fetchRanking throw + KRX 정상 — US 보류, KRX 정상 분류·영속화")
        void classify_usRankingThrows_usWithheldKrxPersisted() {
            // Arrange
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            Stock usStock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock, usStock));

            // KRX 순위 정상(비어있지 않음)
            KisDomesticRankingResponse.RankedStock krxRanked =
                    new KisDomesticRankingResponse.RankedStock("005930", "1", "삼성전자");
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of(krxRanked));
            when(percentileCalculator.calculate(anyList())).thenReturn(Map.of("005930", 10.0));
            // US 순위 throw
            when(overseasRankingClient.fetchRanking()).thenThrow(new RuntimeException("US 순위 실패"));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            // Act
            service.classify();

            // Assert: KRX 종목은 분류·영속화, US 종목은 persistSingle 미호출
            verify(stockGradePersistService).persistSingle(eq(krxStock), any(), any());
            verify(stockGradePersistService, never()).persistSingle(eq(usStock), any(), any());
        }

        @Test
        @DisplayName("시나리오 7: cold-start(0행) + KRX 순위 공백 — classify crash 없이 종료, persistSingle 미호출")
        void classify_coldStartKrxRankingEmpty_noExceptionNoPersist() {
            // Arrange: stock_grades 0행 cold-start, KRX 순위 공백
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of());

            // Act & Assert: crash 없이 종료
            assertThatCode(service::classify).doesNotThrowAnyException();
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("시나리오 7 self-heal: 이후 순위 API 복구 시 persistSingle A/B로 호출")
        void classify_afterRankingRecovery_persistSingleCalledWithGrade() {
            // Arrange: 복구된 KRX 순위 데이터
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            KisDomesticRankingResponse.RankedStock ranked =
                    new KisDomesticRankingResponse.RankedStock("005930", "1", "삼성전자");
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of(ranked));
            when(percentileCalculator.calculate(anyList())).thenReturn(Map.of("005930", 5.0));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            // Act
            service.classify();

            // Assert: 순위 복구 후 A 등급으로 영속화(self-heal)
            verify(stockGradePersistService).persistSingle(eq(krxStock), eq(Grade.A), any());
        }

        @Test
        @DisplayName("Edge: 순위 정상 + 특정 종목만 순위권 밖 — 100.0 fallback(C) 정상 동작")
        void classify_rankingNonEmptyButStockAbsent_fallbackCBehavior() {
            // Arrange: 순위 데이터 존재하나 005930은 순위에 없음 → getOrDefault 100.0 → C
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            // 비어있지 않은 순위 데이터(다른 종목 존재)
            KisDomesticRankingResponse.RankedStock otherRanked =
                    new KisDomesticRankingResponse.RankedStock("000660", "1", "SK하이닉스");
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of(otherRanked));
            when(percentileCalculator.calculate(anyList())).thenReturn(Map.of("000660", 5.0));
            when(gradeClassifier.classify(any())).thenReturn(Grade.C);

            // Act
            service.classify();

            // Assert: 분류 보류 아님 — persistSingle 정상 호출(C)
            verify(stockGradePersistService).persistSingle(eq(krxStock), eq(Grade.C), any());
        }

        @Test
        @DisplayName(
                "시나리오 8a: KRX fetchRanking 비어있지 않으나 모든 dataRank 파싱 불가 — KRX 종목 persistSingle 미호출(보류)")
        void classify_krxAllEntriesUnparseable_krxWithheld() {
            // Arrange: 비어있지 않은 KRX 응답이지만 dataRank가 모두 파싱 불가(" ", "abc")
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            when(domesticRankingClient.fetchRanking())
                    .thenReturn(
                            List.of(
                                    new KisDomesticRankingResponse.RankedStock(
                                            "005930", " ", "삼성전자"),
                                    new KisDomesticRankingResponse.RankedStock(
                                            "000660", "abc", "SK하이닉스")));

            // Act
            service.classify();

            // Assert: 모든 항목 파싱 실패 → entries 공백 → 보류 → persistSingle 미호출
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName(
                "시나리오 8b: US fetchRanking 비어있지 않으나 모든 rank 파싱 불가 — US 종목 persistSingle 미호출(보류)")
        void classify_usAllEntriesUnparseable_usWithheld() {
            // Arrange: 비어있지 않은 US 응답이지만 rank가 모두 파싱 불가
            Stock usStock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(usStock));
            when(overseasRankingClient.fetchRanking())
                    .thenReturn(
                            List.of(
                                    new KisOverseasRankingResponse.RankedStock("AAPL", "N/A"),
                                    new KisOverseasRankingResponse.RankedStock("MSFT", "???")));

            // Act
            service.classify();

            // Assert: 모든 항목 파싱 실패 → entries 공백 → 보류 → persistSingle 미호출
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("시나리오 8c: KRX 일부 파싱 가능 — 파싱 성공 항목으로 정상 분류")
        void classify_krxPartiallyUnparseable_successEntryUsed() {
            // Arrange: 일부는 파싱 불가, 일부는 파싱 가능 → 파싱 성공 항목으로 분류 진행
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            when(domesticRankingClient.fetchRanking())
                    .thenReturn(
                            List.of(
                                    new KisDomesticRankingResponse.RankedStock(
                                            "005930", "1", "삼성전자"),
                                    new KisDomesticRankingResponse.RankedStock(
                                            "000660", "abc", "SK하이닉스")));
            when(percentileCalculator.calculate(anyList())).thenReturn(Map.of("005930", 5.0));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            // Act
            service.classify();

            // Assert: 파싱 성공 1건 → entries 비어있지 않음 → 정상 분류
            verify(stockGradePersistService).persistSingle(eq(krxStock), eq(Grade.A), any());
        }

        @Test
        @DisplayName("시나리오 8d: US 일부 파싱 가능 — 파싱 성공 항목으로 정상 분류")
        void classify_usPartiallyUnparseable_successEntryUsed() {
            // Arrange: 일부 파싱 불가, 일부 파싱 가능
            Stock usStock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(usStock));
            when(overseasRankingClient.fetchRanking())
                    .thenReturn(
                            List.of(
                                    new KisOverseasRankingResponse.RankedStock("AAPL", "1"),
                                    new KisOverseasRankingResponse.RankedStock("MSFT", "N/A")));
            when(percentileCalculator.calculate(anyList())).thenReturn(Map.of("AAPL", 10.0));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            // Act
            service.classify();

            // Assert: 파싱 성공 1건 → entries 비어있지 않음 → 정상 분류
            verify(stockGradePersistService).persistSingle(eq(usStock), eq(Grade.A), any());
        }

        @Test
        @DisplayName("Edge: KRX 종목만 존재, US 순위 빈 결과 — US 보류 판정 미발동(US 종목 없음)")
        void classify_krxOnlyWithUsEmpty_noUsWithhold() {
            // Arrange: KRX 종목만, US 순위는 빈 결과지만 US 종목이 없으므로 보류 판정 무관
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            KisDomesticRankingResponse.RankedStock ranked =
                    new KisDomesticRankingResponse.RankedStock("005930", "1", "삼성전자");
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of(ranked));
            when(percentileCalculator.calculate(anyList())).thenReturn(Map.of("005930", 10.0));
            when(gradeClassifier.classify(any())).thenReturn(Grade.B);

            // Act
            service.classify();

            // Assert: KRX 종목 정상 분류, US overseasRankingClient는 호출되지 않음
            verify(stockGradePersistService).persistSingle(eq(krxStock), eq(Grade.B), any());
            verify(overseasRankingClient, never()).fetchRanking();
        }
    }

    // @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
    @Nested
    @DisplayName("상장일 미상 — listedYearsResolver 위임 (REQ-STOCKMETA-013, M3 1안)")
    class MissingListedDateFallback {

        @Test
        @DisplayName("listedDate=null + OHLCV 없음 — resolver가 0.0 반환 → listedYears < 7 (A 미수여)")
        void classify_nullListedDate_noOhlcv_treatedAsNew() {
            // Arrange — listedYearsResolver가 보수적 신규(0.0)를 반환하는 케이스를 service 수준에서 검증
            Stock stock = buildStock("NEWIPO", Market.NASDAQ, null);
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(listedYearsResolver.resolve(stock))
                    .thenReturn(ListedYearsResolver.NEW_STOCK_FALLBACK_YEARS);
            when(gradeClassifier.classify(any())).thenReturn(Grade.C);

            // Act
            service.classify();

            // Assert — listedYears < 7(신규로 간주), A 미수여
            ArgumentCaptor<GradeInput> captor = ArgumentCaptor.forClass(GradeInput.class);
            verify(gradeClassifier).classify(captor.capture());
            assertThat(captor.getValue().listedYears()).isLessThan(7.0);
            verify(stockGradePersistService)
                    .persistSingle(eq(stock), any(), any(ZonedDateTime.class));
        }

        @Test
        @DisplayName("listedDate=null + OHLCV MIN >= 7년전 — resolver가 100.0 반환 → listedYears >= 7")
        void classify_nullListedDate_ohlcvOldEnough_treatedAsEstablished() {
            // Arrange — listedYearsResolver가 장기 상장(100.0)을 반환하는 케이스
            Stock stock = buildStock("JPM", Market.NYSE, null);
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(listedYearsResolver.resolve(stock))
                    .thenReturn(ListedYearsResolver.ESTABLISHED_FALLBACK_YEARS);
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            // Act
            service.classify();

            // Assert — listedYears >= 7, A 수여 가능
            ArgumentCaptor<GradeInput> captor = ArgumentCaptor.forClass(GradeInput.class);
            verify(gradeClassifier).classify(captor.capture());
            assertThat(captor.getValue().listedYears()).isGreaterThanOrEqualTo(7.0);
            verify(stockGradePersistService)
                    .persistSingle(eq(stock), eq(Grade.A), any(ZonedDateTime.class));
        }

        @Test
        @DisplayName("listedDate=null + OHLCV MIN < 7년전 — resolver가 elapsed 반환 → listedYears < 7")
        void classify_nullListedDate_ohlcvTooRecent_treatedAsNew() {
            // Arrange — listedYearsResolver가 3년(< 7년)을 반환하는 케이스
            Stock stock = buildStock("RECENT", Market.NASDAQ, null);
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(listedYearsResolver.resolve(stock)).thenReturn(3.0);
            when(gradeClassifier.classify(any())).thenReturn(Grade.C);

            // Act
            service.classify();

            // Assert — listedYears < 7
            ArgumentCaptor<GradeInput> captor = ArgumentCaptor.forClass(GradeInput.class);
            verify(gradeClassifier).classify(captor.capture());
            assertThat(captor.getValue().listedYears()).isLessThan(7.0);
        }
    }
}
