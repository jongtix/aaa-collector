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

/**
 * GradeClassificationService 단위 테스트 — 라이브 fetch 기반 classifyDomestic/classifyOverseas 검증.
 *
 * <p>Task 4 REFACTOR: classify() → classifyDomestic() / classifyOverseas() 분리 후 기존 테스트 재매핑. 스냅샷 읽기
 * 전환(feat) 전 라이브 fetch 동작 보존 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GradeClassificationService 단위 테스트 (라이브 fetch 기반)")
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
        lenient()
                .when(domesticRankingClient.fetchRanking())
                .thenReturn(
                        List.of(new KisDomesticRankingResponse.RankedStock("DUMMY", "1", "더미")));
        lenient()
                .when(overseasRankingClient.fetchRanking())
                .thenReturn(List.of(new KisOverseasRankingResponse.RankedStock("DUMMY", "1")));
        lenient().when(percentileCalculator.calculate(anyList())).thenReturn(Map.of());
        lenient().when(gradeClassifier.classify(any())).thenReturn(Grade.C);
        lenient().when(listedYearsResolver.resolve(any())).thenReturn(8.0);
    }

    @Nested
    @DisplayName("시나리오 7 — 상장폐지 종목 제외")
    class ActiveStocksOnly {

        @Test
        @DisplayName("findAllActive()가 반환한 종목만 분류 대상 — classifyDomestic")
        void classifyDomestic_usesOnlyActiveStocks() {
            Stock active = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(active));

            service.classifyDomestic();

            verify(stockRepository).findAllActive();
            verify(gradeClassifier).classify(any());
        }

        @Test
        @DisplayName("활성 종목 없음 — 분류기 호출 없음 (classifyDomestic)")
        void classifyDomestic_noActiveStocks_nothingClassified() {
            when(stockRepository.findAllActive()).thenReturn(List.of());

            service.classifyDomestic();

            verify(gradeClassifier, never()).classify(any());
        }

        @Test
        @DisplayName("findAllActive()가 반환한 종목만 분류 대상 — classifyOverseas")
        void classifyOverseas_usesOnlyActiveStocks() {
            Stock active = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(active));

            service.classifyOverseas();

            verify(stockRepository).findAllActive();
            verify(gradeClassifier).classify(any());
        }
    }

    @Nested
    @DisplayName("시나리오 8 — 시장별 데이터 소스 라우팅")
    class MarketRouting {

        @Test
        @DisplayName("KRX 종목 존재 시 — KisDomesticRankingClient 호출 (classifyDomestic)")
        void classifyDomestic_kospiStock_callsDomesticClient() {
            Stock stock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));

            service.classifyDomestic();

            verify(domesticRankingClient).fetchRanking();
        }

        @Test
        @DisplayName("US 종목 존재 시 — KisOverseasRankingClient 호출 (classifyOverseas)")
        void classifyOverseas_nyseStock_callsOverseasClient() {
            Stock stock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));

            service.classifyOverseas();

            verify(overseasRankingClient).fetchRanking();
        }

        @Test
        @DisplayName("classifyDomestic()은 US 종목에 persistSingle 미호출")
        void classifyDomestic_usStockPresent_notClassified() {
            Stock usStock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(usStock));

            service.classifyDomestic();

            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("classifyOverseas()는 KRX 종목에 persistSingle 미호출")
        void classifyOverseas_krxStockPresent_notClassified() {
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));

            service.classifyOverseas();

            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("시나리오 9 — 등급 산정 후 영속화 위임")
    class PersistenceDelegation {

        @Test
        @DisplayName("등급 산정 후 StockGradePersistService.persistSingle() 호출 — classifyDomestic")
        void classifyDomestic_gradeAssigned_delegatesToPersistService() {
            Stock stock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            service.classifyDomestic();

            verify(stockGradePersistService)
                    .persistSingle(eq(stock), eq(Grade.A), any(ZonedDateTime.class));
        }

        @Test
        @DisplayName("등급 산정 후 StockGradePersistService.persistSingle() 호출 — classifyOverseas")
        void classifyOverseas_gradeAssigned_delegatesToPersistService() {
            Stock stock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            service.classifyOverseas();

            verify(stockGradePersistService)
                    .persistSingle(eq(stock), eq(Grade.A), any(ZonedDateTime.class));
        }
    }

    @Nested
    @DisplayName("시나리오 5/6 — 분류 실패 격리")
    class FailureHandling {

        @Test
        @DisplayName("한 종목 분류 실패 — 나머지 종목 persistSingle() 계속 호출 (classifyDomestic)")
        void classifyDomestic_failureOnOneStock_continuesOthers() {
            Stock fail = buildStock("FAIL", Market.KOSPI, LocalDate.of(2010, 1, 1));
            Stock ok = buildStock("OK", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(fail, ok));
            when(gradeClassifier.classify(any()))
                    .thenThrow(new RuntimeException("분류 실패"))
                    .thenReturn(Grade.B);

            service.classifyDomestic();

            // "OK" 종목은 정상 처리 — persistSingle 1회 호출
            verify(stockGradePersistService).persistSingle(eq(ok), eq(Grade.B), any());
        }

        @Test
        @DisplayName("분류 실패 종목 — persistSingle 호출 없음 (classifyDomestic)")
        void classifyDomestic_failureStock_noPersistCall() {
            Stock stock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(gradeClassifier.classify(any())).thenThrow(new RuntimeException("분류 실패"));

            service.classifyDomestic();

            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("시나리오 4/5/7 — 순위 데이터 결손 분류 보류 (SPEC-COLLECTOR-GRADE-002 REQ-004)")
    class RankingDeficitWithhold {

        @Test
        @DisplayName("warm + KRX fetchRanking 빈 결과 — KRX 종목 persistSingle 미호출(보류)")
        void classifyDomestic_warmKrxRankingEmpty_krxStockNotPersisted() {
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of());

            service.classifyDomestic();

            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("US fetchRanking throw — US 보류 (classifyOverseas)")
        void classifyOverseas_usRankingThrows_usWithheld() {
            Stock usStock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(usStock));
            when(overseasRankingClient.fetchRanking()).thenThrow(new RuntimeException("US 순위 실패"));

            service.classifyOverseas();

            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("KRX 결손, US 정상 — 시장 독립: US classifyOverseas 정상 분류")
        void classifyOverseas_usNormal_krxDeficitUnrelated() {
            Stock usStock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(usStock));
            KisOverseasRankingResponse.RankedStock usRanked =
                    new KisOverseasRankingResponse.RankedStock("AAPL", "1");
            when(overseasRankingClient.fetchRanking()).thenReturn(List.of(usRanked));
            when(percentileCalculator.calculate(anyList())).thenReturn(Map.of("AAPL", 5.0));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            service.classifyOverseas();

            verify(stockGradePersistService).persistSingle(eq(usStock), eq(Grade.A), any());
        }

        @Test
        @DisplayName("cold-start(0행) + KRX 순위 공백 — classifyDomestic crash 없이 종료")
        void classifyDomestic_coldStartKrxRankingEmpty_noExceptionNoPersist() {
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of());

            assertThatCode(service::classifyDomestic).doesNotThrowAnyException();
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("self-heal: 순위 API 복구 시 classifyDomestic persistSingle A/B로 호출")
        void classifyDomestic_afterRankingRecovery_persistSingleCalledWithGrade() {
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            KisDomesticRankingResponse.RankedStock ranked =
                    new KisDomesticRankingResponse.RankedStock("005930", "1", "삼성전자");
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of(ranked));
            when(percentileCalculator.calculate(anyList())).thenReturn(Map.of("005930", 5.0));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            service.classifyDomestic();

            verify(stockGradePersistService).persistSingle(eq(krxStock), eq(Grade.A), any());
        }

        @Test
        @DisplayName("순위 정상 + 특정 종목만 순위권 밖 — 100.0 fallback(C) 정상 동작 (classifyDomestic)")
        void classifyDomestic_rankingNonEmptyButStockAbsent_fallbackCBehavior() {
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            KisDomesticRankingResponse.RankedStock otherRanked =
                    new KisDomesticRankingResponse.RankedStock("000660", "1", "SK하이닉스");
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of(otherRanked));
            when(percentileCalculator.calculate(anyList())).thenReturn(Map.of("000660", 5.0));
            when(gradeClassifier.classify(any())).thenReturn(Grade.C);

            service.classifyDomestic();

            // 분류 보류 아님 — persistSingle 정상 호출(C)
            verify(stockGradePersistService).persistSingle(eq(krxStock), eq(Grade.C), any());
        }

        @Test
        @DisplayName("KRX fetchRanking 비어있지 않으나 모든 dataRank 파싱 불가 — KRX 보류")
        void classifyDomestic_krxAllEntriesUnparseable_krxWithheld() {
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            when(domesticRankingClient.fetchRanking())
                    .thenReturn(
                            List.of(
                                    new KisDomesticRankingResponse.RankedStock(
                                            "005930", " ", "삼성전자"),
                                    new KisDomesticRankingResponse.RankedStock(
                                            "000660", "abc", "SK하이닉스")));

            service.classifyDomestic();

            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("US fetchRanking 비어있지 않으나 모든 rank 파싱 불가 — US 보류")
        void classifyOverseas_usAllEntriesUnparseable_usWithheld() {
            Stock usStock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(usStock));
            when(overseasRankingClient.fetchRanking())
                    .thenReturn(
                            List.of(
                                    new KisOverseasRankingResponse.RankedStock("AAPL", "N/A"),
                                    new KisOverseasRankingResponse.RankedStock("MSFT", "???")));

            service.classifyOverseas();

            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("KRX 일부 파싱 가능 — 파싱 성공 항목으로 정상 분류 (classifyDomestic)")
        void classifyDomestic_krxPartiallyUnparseable_successEntryUsed() {
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

            service.classifyDomestic();

            verify(stockGradePersistService).persistSingle(eq(krxStock), eq(Grade.A), any());
        }

        @Test
        @DisplayName("US 일부 파싱 가능 — 파싱 성공 항목으로 정상 분류 (classifyOverseas)")
        void classifyOverseas_usPartiallyUnparseable_successEntryUsed() {
            Stock usStock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(usStock));
            when(overseasRankingClient.fetchRanking())
                    .thenReturn(
                            List.of(
                                    new KisOverseasRankingResponse.RankedStock("AAPL", "1"),
                                    new KisOverseasRankingResponse.RankedStock("MSFT", "N/A")));
            when(percentileCalculator.calculate(anyList())).thenReturn(Map.of("AAPL", 10.0));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            service.classifyOverseas();

            verify(stockGradePersistService).persistSingle(eq(usStock), eq(Grade.A), any());
        }

        @Test
        @DisplayName("KRX 종목만 존재, classifyDomestic 정상 — overseasRankingClient 미호출")
        void classifyDomestic_krxOnly_overseasClientNotCalled() {
            Stock krxStock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(krxStock));
            KisDomesticRankingResponse.RankedStock ranked =
                    new KisDomesticRankingResponse.RankedStock("005930", "1", "삼성전자");
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of(ranked));
            when(percentileCalculator.calculate(anyList())).thenReturn(Map.of("005930", 10.0));
            when(gradeClassifier.classify(any())).thenReturn(Grade.B);

            service.classifyDomestic();

            verify(stockGradePersistService).persistSingle(eq(krxStock), eq(Grade.B), any());
            verify(overseasRankingClient, never()).fetchRanking();
        }
    }

    // @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
    @Nested
    @DisplayName("상장일 미상 — listedYearsResolver 위임 (REQ-STOCKMETA-013)")
    class MissingListedDateFallback {

        @Test
        @DisplayName("listedDate=null + OHLCV 없음 — resolver가 0.0 반환 → listedYears < 7")
        void classifyDomestic_nullListedDate_noOhlcv_treatedAsNew() {
            Stock stock = buildStock("NEWIPO", Market.NASDAQ, null);
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(listedYearsResolver.resolve(stock))
                    .thenReturn(ListedYearsResolver.NEW_STOCK_FALLBACK_YEARS);
            when(gradeClassifier.classify(any())).thenReturn(Grade.C);

            service.classifyOverseas();

            ArgumentCaptor<GradeInput> captor = ArgumentCaptor.forClass(GradeInput.class);
            verify(gradeClassifier).classify(captor.capture());
            assertThat(captor.getValue().listedYears()).isLessThan(7.0);
            verify(stockGradePersistService)
                    .persistSingle(eq(stock), any(), any(ZonedDateTime.class));
        }

        @Test
        @DisplayName("listedDate=null + OHLCV MIN >= 7년전 — resolver가 100.0 반환 → listedYears >= 7")
        void classifyOverseas_nullListedDate_ohlcvOldEnough_treatedAsEstablished() {
            Stock stock = buildStock("JPM", Market.NYSE, null);
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(listedYearsResolver.resolve(stock))
                    .thenReturn(ListedYearsResolver.ESTABLISHED_FALLBACK_YEARS);
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            service.classifyOverseas();

            ArgumentCaptor<GradeInput> captor = ArgumentCaptor.forClass(GradeInput.class);
            verify(gradeClassifier).classify(captor.capture());
            assertThat(captor.getValue().listedYears()).isGreaterThanOrEqualTo(7.0);
            verify(stockGradePersistService)
                    .persistSingle(eq(stock), eq(Grade.A), any(ZonedDateTime.class));
        }

        @Test
        @DisplayName("listedDate=null + OHLCV MIN < 7년전 — resolver가 elapsed 반환 → listedYears < 7")
        void classifyOverseas_nullListedDate_ohlcvTooRecent_treatedAsNew() {
            Stock stock = buildStock("RECENT", Market.NASDAQ, null);
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(listedYearsResolver.resolve(stock)).thenReturn(3.0);
            when(gradeClassifier.classify(any())).thenReturn(Grade.C);

            service.classifyOverseas();

            ArgumentCaptor<GradeInput> captor = ArgumentCaptor.forClass(GradeInput.class);
            verify(gradeClassifier).classify(captor.capture());
            assertThat(captor.getValue().listedYears()).isLessThan(7.0);
        }
    }
}
