package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.stock.DailyOhlcv;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 미국 일봉 멱등 저장 통합 테스트 (MySQL Testcontainer — H2는 {@code INSERT IGNORE} 네이티브 미재현).
 *
 * <p>실제 {@link DailyOhlcvRepository}/{@link StockRepository}를 사용하고 KIS 호출({@link
 * BatchRestExecutor})·키 분산({@link HealthyKeyRoundRobinDistributor})만 모킹하여 원주가(MODP=0)·zdiv=4
 * 무손실·tamt BIGINT·ETF 행·멱등성을 검증한다. (REQ-OVOH-003/003a/004/022)
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("OverseasDailyOhlcvCollectionService 멱등 통합 테스트")
class OverseasDailyOhlcvCollectionServiceIntegrationTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchRestExecutor batchRestExecutor;
    @MockitoBean private HealthyKeyRoundRobinDistributor distributor;

    @Autowired private OverseasDailyOhlcvCollectionService service;
    @Autowired private StockRepository stockRepository;
    @Autowired private DailyOhlcvRepository dailyOhlcvRepository;

    /** ET 당일 행 가드를 회피하기 위해 응답 행보다 한참 미래의 기준일을 사용한다. */
    private static final LocalDate FUTURE_TODAY = LocalDate.of(2030, 1, 1);

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private Stock savedStock(String symbol, Market market, AssetType assetType) {
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트_" + symbol)
                        .market(market)
                        .assetType(assetType)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build());
    }

    private KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row(
            String xymd,
            String open,
            String high,
            String low,
            String clos,
            String tvol,
            String tamt) {
        return new KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow(
                xymd, clos, open, high, low, tvol, tamt);
    }

    private KisOverseasDailyOhlcvResponse response(
            List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> rows) {
        return new KisOverseasDailyOhlcvResponse(
                "0",
                "MCA00000",
                "정상",
                new KisOverseasDailyOhlcvResponse.Output1("DNASAAPL", "4", "100"),
                rows);
    }

    private void stub(Stock stock, KisOverseasDailyOhlcvResponse resp) {
        // stockRepository는 실제 빈 — 저장된 미국 STOCK/ETF가 findAllActiveOverseasTradable()로 조회된다.
        when(distributor.distribute(any())).thenReturn(Map.of(ISA, List.of(stock)));
        when(batchRestExecutor.execute(
                        eq(ISA),
                        any(),
                        anyString(),
                        eq(KisOverseasDailyOhlcvResponse.class),
                        eq(stock.getSymbol())))
                .thenReturn(BatchResult.success(resp));
    }

    private DailyOhlcv findRow(Stock stock, LocalDate date) {
        return dailyOhlcvRepository
                .findByStockIdAndTradeDateIn(stock.getId(), List.of(date))
                .getFirst();
    }

    @Nested
    @DisplayName("멱등 저장 / 원주가 / 무손실 (AC-SAVE-1, AC-SAVE-2)")
    class IdempotentSave {

        @Test
        @DisplayName("신규 행 저장 — zdiv=4 가격 BigDecimal 4자리 무손실, tamt BIGINT 저장")
        void collect_newRow_savedLossless() {
            // Arrange — when() 스텁이므로 실제 findAll로 조회되지 않게 미리 저장 후 stockRepository는 모킹
            Stock stock = savedStock("AAPL", Market.NASDAQ, AssetType.STOCK);
            LocalDate date = LocalDate.of(2026, 6, 17);
            stub(
                    stock,
                    response(
                            List.of(
                                    row(
                                            "20260617",
                                            "300.8450",
                                            "302.0700",
                                            "294.3600",
                                            "295.9500",
                                            "42745060",
                                            "12697950974"))));

            // Act
            service.collect(FUTURE_TODAY);
            dailyOhlcvRepository.flush();

            // Assert
            DailyOhlcv saved = findRow(stock, date);
            assertThat(saved.getClosePrice()).isEqualByComparingTo(new BigDecimal("295.9500"));
            assertThat(saved.getClosePrice().scale()).isEqualTo(4);
            assertThat(saved.getOpenPrice()).isEqualByComparingTo(new BigDecimal("300.8450"));
            assertThat(saved.getVolume()).isEqualTo(42_745_060L);
            assertThat(saved.getTradingValue()).isEqualTo(12_697_950_974L); // tamt > 국내 VOLUME_MAX
        }

        @Test
        @DisplayName("재수집 — 동일 (stock_id, trade_date) 중복 미증가, 최초 값 보존(UPDATE 미발생)")
        void collect_recollect_noDuplicateNoUpdate() {
            // Arrange — 최초 수집
            Stock stock = savedStock("MSFT", Market.NASDAQ, AssetType.STOCK);
            LocalDate date = LocalDate.of(2026, 6, 17);
            BigDecimal originalClose = new BigDecimal("295.9500");
            stub(
                    stock,
                    response(
                            List.of(
                                    row(
                                            "20260617",
                                            "300.8450",
                                            "302.0700",
                                            "294.3600",
                                            "295.9500",
                                            "42745060",
                                            "12697950974"))));
            service.collect(FUTURE_TODAY);
            dailyOhlcvRepository.flush();

            // Act — 다른 값으로 재수집(같은 키)
            stub(
                    stock,
                    response(
                            List.of(
                                    row(
                                            "20260617",
                                            "999.0000",
                                            "999.0000",
                                            "999.0000",
                                            "999.0000",
                                            "1",
                                            "1"))));
            service.collect(FUTURE_TODAY);
            dailyOhlcvRepository.flush();

            // Assert — 행 수 1 유지, 최초 종가 보존
            assertThat(dailyOhlcvRepository.countByStockId(stock.getId())).isEqualTo(1L);
            assertThat(findRow(stock, date).getClosePrice()).isEqualByComparingTo(originalClose);
        }

        @Test
        @DisplayName("ETF 행 저장 — SPY(AMEX) 정상 적재(STOCK과 동일 경로)")
        void collect_etfRow_saved() {
            // Arrange
            Stock spy = savedStock("SPY", Market.AMEX, AssetType.ETF);
            LocalDate date = LocalDate.of(2026, 6, 17);
            stub(
                    spy,
                    response(
                            List.of(
                                    row(
                                            "20260617",
                                            "600.1000",
                                            "601.0000",
                                            "599.0000",
                                            "600.5000",
                                            "51000000",
                                            "30600000000"))));

            // Act
            service.collect(FUTURE_TODAY);
            dailyOhlcvRepository.flush();

            // Assert
            assertThat(dailyOhlcvRepository.countByStockId(spy.getId())).isEqualTo(1L);
            assertThat(findRow(spy, date).getTradingValue()).isEqualTo(30_600_000_000L);
        }
    }
}
