package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.market.session.UsMarketSessionGate;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 해외 현금배당 백필 persist 트랜잭션 경계 + INSERT IGNORE 멱등성 통합 테스트
 * (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001 REQ-ODW-071/072, AC-ODW-008/009, M5).
 *
 * <p>fetch 단계의 매핑·rawRowCount 결정성·예외 전파는 {@link OverseasDividendBackfillTest}(mock 기반 단위 테스트)가 이미
 * 커버한다. 본 IT는 실 MySQL(Testcontainers)에서만 재현 가능한 두 계약을 검증한다 — (1) {@code @Transactional(propagation
 * = MANDATORY)}가 활성 트랜잭션 없이 호출될 때 즉시 실패({@code OverseasSplitIntegrationTest}의 동일 패턴, mock으로는 프록시
 * 부재로 재현 불가), (2) {@code corporate_events} native {@code INSERT IGNORE}가 유니크 키 {@code (stock_id,
 * event_type, event_date, event_subtype)} 충돌을 흡수해 재실행·청크 경계 중복 모두 행 수를 증가시키지 않음(REQ-ODW-071,
 * AC-ODW-008/009 — {@code RevSplitBackfillIdempotencyIT}와 동일 패턴).
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("해외 현금배당 백필 persist MANDATORY 트랜잭션 가드 IT")
@Tag("integration")
class OverseasDividendBackfillIntegrationTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    @MockitoBean private GuardedKisExecutor guardedKisExecutor;
    @MockitoBean private KeyLeaseRegistry keyLeaseRegistry;

    // OverseasSplitIntegrationTest와 동일 사유 — ApplicationReadyEvent 기동 시
    // MarketSessionGateRefresher가 KisHolidayClient.fetchCalendar를 즉시 호출하므로 스텁 없이 두면
    // 컨텍스트 기동이 NPE로 깨진다(REQ-WM-007 MA-01).
    @MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
    private com.aaa.collector.kis.holiday.KisHolidayClient kisHolidayClient;

    @MockitoBean private UsMarketSessionGate usMarketOpenGate;

    @Autowired private OverseasDividendBackfillService service;
    @Autowired private StockRepository stockRepository;
    @Autowired private CorporateEventRepository corporateEventRepository;

    @BeforeEach
    void setUp() {
        LeaseSession leaseSession = Mockito.mock(LeaseSession.class);
        when(usMarketOpenGate.isOpenDay(any())).thenReturn(true);
        when(keyLeaseRegistry.openSession()).thenReturn(leaseSession);
        when(leaseSession.isEmpty()).thenReturn(false);
    }

    @Test
    @DisplayName(
            "REQ-ODW-072: persistWindowForBackfill 트랜잭션 없이 호출 → IllegalTransactionStateException")
    void persistWithoutTransaction_throws() {
        OverseasDividendBackfillFetch fetch = new OverseasDividendBackfillFetch(List.of(), null, 0);

        assertThatThrownBy(() -> service.persistWindowForBackfill(fetch))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Stock savedStock(String symbol) {
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .active(true)
                        .build());
    }

    private CorporateEvent overseasDividend(Stock stock, LocalDate recordDt) {
        return CorporateEvent.builder()
                .stock(stock)
                .eventType(EventType.DIVIDEND)
                .eventDate(recordDt)
                .exDividendDate(recordDt.minusDays(2))
                .eventSubtype("일반배당")
                .payDate(recordDt.plusDays(21))
                .cashAmount(new BigDecimal("0.50000"))
                .currencyCode("USD")
                .build();
    }

    @Test
    @Transactional
    @DisplayName("AC-ODW-008: 백필 persist 2회 재실행 — 행 수 불변(INSERT IGNORE 멱등), 정기 수집·백필 중복 적재 방지")
    void backfillPersistTwice_rowCountUnchanged_idempotent() {
        // Arrange
        Stock stock = savedStock("TSM");
        LocalDate recordDt = LocalDate.of(2026, 5, 11);
        OverseasDividendBackfillFetch fetch =
                new OverseasDividendBackfillFetch(
                        List.of(overseasDividend(stock, recordDt)), recordDt, 1);

        // Act — 1회차 적재
        BackfillWindowResult first = service.persistWindowForBackfill(fetch);

        // Assert — 1행 적재
        assertThat(first.rowCount()).isEqualTo(1);
        assertThat(first.rawRowCount()).isEqualTo(1);
        assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);

        // Act — 2회차 재실행(동일 유니크 키: stock_id, event_type, event_date, event_subtype)
        service.persistWindowForBackfill(fetch);

        // Assert — INSERT IGNORE로 중복 무시, 행 수 불변, 예외 없음(SQL 1142 미발생)
        assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
    }

    @Test
    @Transactional
    @DisplayName(
            "AC-ODW-009: rights-by-ice 서브윈도우 청크 경계에서 동일 회차가 양쪽 청크에 중복 반환돼도"
                    + " INSERT IGNORE가 흡수해 최종 행 수는 청크 분할 이전과 동일하게 유지된다")
    void chunkBoundaryDuplicateRows_singleFetch_collapsedByInsertIgnore() {
        // Arrange — 인접 두 청크(REQ-ODW-051b, 1일 중첩)가 경계 회차를 각각 독립 조회해 동일 회차를 중복 반환한 상황을 재현
        Stock stock = savedStock("O");
        LocalDate boundaryRecordDt = LocalDate.of(2022, 1, 1);
        List<CorporateEvent> duplicatedAcrossChunks =
                List.of(
                        overseasDividend(stock, boundaryRecordDt),
                        overseasDividend(stock, boundaryRecordDt));
        OverseasDividendBackfillFetch fetch =
                new OverseasDividendBackfillFetch(duplicatedAcrossChunks, boundaryRecordDt, 2);

        // Act
        service.persistWindowForBackfill(fetch);

        // Assert — 단일 배치 내 중복 2건이 유니크 키 충돌로 흡수되어 실제 저장 행은 1건뿐(가상의 단일 광폭 콜과 동일)
        assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
    }
}
