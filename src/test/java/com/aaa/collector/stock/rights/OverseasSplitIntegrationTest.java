package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 해외 SPLIT 수집 end-to-end 통합 테스트 (SPEC-COLLECTOR-OVERSEAS-SPLIT-001, AC-1/4/7/8/12).
 *
 * <p>Testcontainers {@code mysql:8.4}로 실제 DB에 INSERT IGNORE 적재하며 AAPL 3행 dedup·CRWD 별개 이벤트
 * 미병합·멱등성(중복 미증가)·백필 persist MANDATORY 가드를 검증한다(H2는 INSERT IGNORE 미재현). CTRGT011R HTTP만 {@link
 * GuardedKisExecutor} mock으로 대체하고 프리페처·매퍼·인서터는 실제 빈을 사용한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("해외 SPLIT 수집 통합 테스트 (INSERT IGNORE·dedup·멱등)")
@Tag("integration")
class OverseasSplitIntegrationTest {

    private static final String TR_ID = "CTRGT011R";
    private static final String SPLIT = OverseasSplitMapper.RGHT_TYPE_SPLIT;
    private static final String MERGE = OverseasSplitMapper.RGHT_TYPE_MERGE;

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

    // main 병합(SPEC-COLLECTOR-USMKT-001)으로 MarketSessionGateRefresher가 ApplicationReadyEvent에서
    // KisHolidayClient.fetchCalendar를 즉시 호출한다(REQ-WM-007 MA-01) — @BeforeEach 스텁보다 먼저
    // 실행되므로 guardedKisExecutor/keyLeaseRegistry를 스텁 없이 두면 이 호출이 NPE로 컨텍스트 기동을
    // 깨뜨린다. KisHolidayClient 자체를 RETURNS_MOCKS로 모킹해 흡수한다 — fetchCalendar의 반환 타입은
    // 제네릭 소거가 없는 구체 List<HolidayRow>라 deep-mock이 안전하게 빈 리스트처럼 동작한다(반대로
    // GuardedKisExecutor.execute(...)는 제네릭 반환형이라 RETURNS_MOCKS 적용 시 ClassCastException
    // 유발 — 이 SPEC과 무관한 본 클라이언트 자체를 모킹해 그 경로를 아예 타지 않게 우회).
    @MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
    private com.aaa.collector.kis.holiday.KisHolidayClient kisHolidayClient;

    // main 병합(SPEC-COLLECTOR-USMKT-001)으로 UsMarketOpenGate는 인터페이스가 되고 유일 구현체
    // UsMarketSessionGate를 CoverageRefresher가 구체 타입으로 직접 주입받는다 — 인터페이스로 모킹하면
    // 같은 빈 이름에 타입 불일치(BeanNotOfRequiredTypeException)가 발생하므로 구체 타입으로 모킹한다.
    @MockitoBean private UsMarketSessionGate usMarketOpenGate;

    @Autowired private OverseasSplitCollectionService service;
    @Autowired private StockRepository stockRepository;
    @Autowired private CorporateEventRepository corporateEventRepository;

    @BeforeEach
    void setUp() {
        // LeaseSession은 final이라 @Mock 대신 직접 mock 생성 — GuardedKisExecutor mock이 세션을 무시하므로 스텁만 필요.
        LeaseSession leaseSession = Mockito.mock(LeaseSession.class);
        when(usMarketOpenGate.isOpenDay(any())).thenReturn(true);
        when(keyLeaseRegistry.openSession()).thenReturn(leaseSession);
        when(leaseSession.isEmpty()).thenReturn(false);
    }

    private Stock saveStock(String symbol) {
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트_" + symbol)
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .active(true) // 2축 필터 부활(SPEC-COLLECTOR-WLSYNC-008) — 이 fixture는 시장 유효 종목
                        .build());
    }

    private KisPeriodRightsResponse.PeriodRightsRow splitRow(
            String pdno, String bassDt, String acplBassDt, String rt) {
        return new KisPeriodRightsResponse.PeriodRightsRow(
                bassDt,
                SPLIT,
                pdno,
                pdno + " INC",
                "512",
                "US0000000000",
                acplBassDt,
                "",
                "",
                "0",
                rt,
                "USD",
                "",
                "",
                "",
                "0",
                "0",
                "0",
                "0",
                "Y");
    }

    private KisPeriodRightsResponse response(List<KisPeriodRightsResponse.PeriodRightsRow> rows) {
        return new KisPeriodRightsResponse("0", "MCA00000", "정상", rows, null, null);
    }

    private ArgumentMatcher<Function<UriBuilder, URI>> rightTypeIs(String rghtTypeCd) {
        return uriCustomizer -> {
            if (uriCustomizer == null) {
                return false;
            }
            URI uri = uriCustomizer.apply(UriComponentsBuilder.newInstance());
            return uri.toString().contains("RGHT_TYPE_CD=" + rghtTypeCd);
        };
    }

    private void stubSplit(List<KisPeriodRightsResponse.PeriodRightsRow> rows)
            throws InterruptedException {
        when(guardedKisExecutor.execute(
                        any(LeaseSession.class),
                        argThat(rightTypeIs(SPLIT)),
                        eq(TR_ID),
                        eq(KisPeriodRightsResponse.class),
                        anyString()))
                .thenReturn(response(rows));
        when(guardedKisExecutor.execute(
                        any(LeaseSession.class),
                        argThat(rightTypeIs(MERGE)),
                        eq(TR_ID),
                        eq(KisPeriodRightsResponse.class),
                        anyString()))
                .thenReturn(response(List.of()));
    }

    @Test
    @Transactional
    @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
    @DisplayName(
            "AC-1/AC-4: AAPL 3행(주말 2+평일 1) → dedup 1행 저장, event_date=2020-08-31·rate=4.0000·분할")
    void aaplThreeRows_dedupToOneRowInDb() throws Exception {
        Stock aapl = saveStock("AAPL");
        stubSplit(
                List.of(
                        splitRow("AAPL", "20200829", "20200827", "400.0"),
                        splitRow("AAPL", "20200830", "20200828", "400.0"),
                        splitRow("AAPL", "20200831", "20200831", "400.0")));

        service.collect();

        List<CorporateEvent> events = corporateEventRepository.findAll();
        assertThat(events).hasSize(1);
        CorporateEvent e = events.getFirst();
        assertThat(e.getEventType()).isEqualTo(EventType.SPLIT);
        assertThat(e.getEventSubtype()).isEqualTo("분할");
        assertThat(e.getEventDate()).isEqualTo(LocalDate.of(2020, 8, 31));
        assertThat(e.getStockRate()).isEqualByComparingTo(new BigDecimal("4.0000"));
        assertThat(e.getStock().getId()).isEqualTo(aapl.getId());
    }

    @Test
    @Transactional
    @DisplayName("AC-12: CRWD 별개 분할 2건(300↔400) → 병합되지 않고 2행 저장")
    void crwdDistinctEvents_twoRowsInDb() throws Exception {
        Stock crwd = saveStock("CRWD");
        stubSplit(
                List.of(
                        splitRow("CRWD", "20230815", "20230815", "300.0"),
                        splitRow("CRWD", "20240819", "20240819", "400.0")));

        service.collect();

        assertThat(corporateEventRepository.countByStockId(crwd.getId())).isEqualTo(2L);
    }

    @Test
    @Transactional
    @DisplayName("AC-7: 동일 분할 2회 수집 → INSERT IGNORE 멱등, 행 수 불변(1건)")
    void idempotentReCollect_rowCountUnchanged() throws Exception {
        Stock aapl = saveStock("AAPL");
        stubSplit(List.of(splitRow("AAPL", "20200831", "20200831", "400.0")));

        service.collect();
        service.collect();

        assertThat(corporateEventRepository.countByStockId(aapl.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-8: persistWindowForBackfill 트랜잭션 없이 호출 → IllegalTransactionStateException")
    void persistWithoutTransaction_throws() {
        OverseasSplitBackfillFetch fetch = new OverseasSplitBackfillFetch(List.of(), null, 0);

        assertThatThrownBy(() -> service.persistWindowForBackfill(fetch))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
