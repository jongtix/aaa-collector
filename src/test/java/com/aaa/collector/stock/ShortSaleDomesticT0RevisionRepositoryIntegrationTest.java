package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.SharedMySqlContainer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link ShortSaleDomesticRepository#findT0RevisionCandidateBatch}/{@link
 * ShortSaleDomesticRepository#updateT0RevisionCorrection} 통합 테스트 — 신규 JPQL {@code FUNCTION('DATE',
 * s.createdAt)}가 실 MySQL(Testcontainers)에서 T+0 시그니처를 올바르게 판별하는지 확인한다
 * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-010~012, -021, plan.md §M5/§M9).
 *
 * <p>M5는 이 JPQL을 단위(mock) 테스트로만 검증했다 — {@code FUNCTION('DATE', ...)}가 실제 MySQL 방언에서 컴파일·실행되는지는
 * progress.md §E.2 M5 "잔여 위험"이 M9로 명시 이연했다. {@code created_at}은 시각(DATETIME)이고 {@code trade_date}는
 * 날짜(DATE)라 두 값이 우연히 같은 날짜라도 시각 성분이 다를 수 있다는 점(T+0 시그니처 = "당일 수집") 자체가 이 JPQL의 존재 이유이므로, 원시 SQL로
 * {@code created_at}을 직접 제어해 시그니처 일치/불일치 케이스를 재현한다({@link ShortSaleInserter}는 항상 {@code NOW()}를 쓰므로
 * 결정적 재현에 부적합 — 원시 INSERT로 우회).
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("ShortSaleDomesticRepository T0R 소급 정정 통합 테스트 (REQ-T0R-010~012, -021)")
@Tag("integration")
class ShortSaleDomesticT0RevisionRepositoryIntegrationTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 공유 컨테이너 패턴(SharedMySqlContainer 참조).
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    /**
     * REQ-T0R-011 하한 리터럴 — {@code
     * ShortSaleDomesticT0RevisionCorrectionService.CLOSING_WINDOW_START_DATE}와 동일 값.
     */
    private static final LocalDate WINDOW_START = LocalDate.of(2026, 6, 29);

    private static final LocalDate WINDOW_END = LocalDate.of(2026, 8, 6);

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;

    @Autowired private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private DataSource dataSource;

    private Stock savedStock(String symbol) {
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build());
    }

    /**
     * 원시 INSERT — {@code created_at}을 명시적으로 제어해 T+0 시그니처(DATE(created_at)=trade_date) 일치/불일치를 결정적으로
     * 재현한다. {@link com.aaa.collector.stock.supply.ShortSaleInserter}는 항상 {@code NOW()}를 쓰므로 이 목적에
     * 부적합하다.
     */
    private long insertRawRow(Stock stock, LocalDate tradeDate, LocalDateTime createdAt) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update(
                """
                INSERT INTO short_sale_domestic
                    (stock_id, trade_date, short_sell_qty, short_sell_vol_rate, short_sell_amt,
                     short_sell_amt_rate, short_sell_acc_qty, short_sell_acc_qty_rate,
                     short_sell_acc_amt, short_sell_acc_amt_rate, created_at, updated_at)
                VALUES (?, ?, 10000, 3.5000, 0, 0, 0, 0, 0, 0, ?, ?)
                """,
                stock.getId(),
                tradeDate,
                createdAt,
                createdAt);
        Long id =
                jdbc.queryForObject(
                        "SELECT id FROM short_sale_domestic WHERE stock_id = ? AND trade_date = ?",
                        Long.class,
                        stock.getId(),
                        tradeDate);
        return Objects.requireNonNull(id, "방금 삽입한 short_sale_domestic 행을 찾지 못함");
    }

    @Nested
    @DisplayName("findT0RevisionCandidateBatch — T+0 시그니처 판별 (FUNCTION('DATE', s.createdAt))")
    class FindCandidates {

        @Test
        @DisplayName("DATE(created_at) == trade_date + 구간 내부 — 대상으로 조회됨")
        void t0SignatureWithinWindow_isCandidate() {
            Stock stock = savedStock("T0R001");
            LocalDate tradeDate = LocalDate.of(2026, 7, 20);
            insertRawRow(stock, tradeDate, LocalDateTime.of(2026, 7, 20, 19, 5)); // 같은 날 19:05 수집

            List<ShortSaleDomestic> batch =
                    shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                            WINDOW_START, WINDOW_END, 0L, PageRequest.of(0, 10));

            assertThat(batch).hasSize(1);
            assertThat(batch.getFirst().getTradeDate()).isEqualTo(tradeDate);
        }

        @Test
        @DisplayName("DATE(created_at) != trade_date(후속 백필로 늦게 수집됨) — 대상에서 제외")
        void nonT0Signature_isExcluded() {
            Stock stock = savedStock("T0R002");
            LocalDate tradeDate = LocalDate.of(2026, 7, 20);
            // 거래일 다음날 새벽에 백필로 수집됨 — T+0 시그니처가 아니다.
            insertRawRow(stock, tradeDate, LocalDateTime.of(2026, 7, 21, 2, 0));

            List<ShortSaleDomestic> batch =
                    shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                            WINDOW_START, WINDOW_END, 0L, PageRequest.of(0, 10));

            assertThat(batch).isEmpty();
        }

        @Test
        @DisplayName("T+0 시그니처이나 구간(WINDOW_START 이전) 밖 — 대상에서 제외")
        void t0SignatureOutsideWindow_isExcluded() {
            Stock stock = savedStock("T0R003");
            LocalDate tradeDate = WINDOW_START.minusDays(1); // 구간 하한 직전
            insertRawRow(stock, tradeDate, tradeDate.atTime(19, 0));

            List<ShortSaleDomestic> batch =
                    shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                            WINDOW_START, WINDOW_END, 0L, PageRequest.of(0, 10));

            assertThat(batch).isEmpty();
        }

        @Test
        @DisplayName("T+0 시그니처 + 구간 상한(inclusive) 경계 — 대상으로 조회됨")
        void t0SignatureAtUpperBound_isCandidate() {
            Stock stock = savedStock("T0R004");
            insertRawRow(stock, WINDOW_END, WINDOW_END.atTime(19, 0));

            List<ShortSaleDomestic> batch =
                    shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                            WINDOW_START, WINDOW_END, 0L, PageRequest.of(0, 10));

            assertThat(batch).hasSize(1);
        }
    }

    @Nested
    @DisplayName("updateT0RevisionCorrection — 라이브 재조회 확정치 원자적 반영")
    class UpdateCorrection {

        @Test
        @DisplayName(
                "qty·rate 갱신 후 이후 대상 조회에서 제외(T+0 시그니처 자체는 무변경이므로 계속 대상일 수 있음 — "
                        + "재실행 시 동일 값으로 재확정되는 멱등 동작만 확인)")
        void updateT0RevisionCorrection_persistsQtyAndRate() {
            Stock stock = savedStock("T0R005");
            LocalDate tradeDate = LocalDate.of(2026, 7, 20);
            long id = insertRawRow(stock, tradeDate, LocalDateTime.of(2026, 7, 20, 19, 5));

            int updated =
                    shortSaleDomesticRepository.updateT0RevisionCorrection(
                            id, 25_700L, new BigDecimal("2.57"));

            assertThat(updated).isEqualTo(1);
            ShortSaleDomestic reloaded = shortSaleDomesticRepository.findById(id).orElseThrow();
            assertThat(reloaded.getShortSellQty()).isEqualTo(25_700L);
            assertThat(reloaded.getShortSellVolRate()).isEqualByComparingTo("2.57");
            // T+0 시그니처(created_at) 자체는 이 UPDATE의 대상이 아니므로 여전히 T+0 후보 조회에 남는다
            // (REQ-T0R-012 자동 재시도 없음 — 재실행 대상 판별은 완료 마커(M7)가 별도 담당).
            assertThat(
                            shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                                    WINDOW_START, WINDOW_END, 0L, PageRequest.of(0, 10)))
                    .anySatisfy(r -> assertThat(r.getId()).isEqualTo(id));
        }
    }
}
