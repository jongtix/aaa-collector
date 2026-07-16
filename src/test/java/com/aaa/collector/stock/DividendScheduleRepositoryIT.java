package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.SharedMySqlContainer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 국내 배당 일정 → corporate_events 적재 IT (Testcontainers MySQL {@code mysql:8.4},
 * SPEC-COLLECTOR-DIVIDEND-FIX-001 T5, AC-4).
 *
 * <p><b>범위 한정(형제 {@link com.aaa.collector.stock.rights.OverseasRightsRepositoryIT}와 동일 원칙)</b>:
 * {@link CorporateEventRepositoryTest}가 이미 4컬럼 unique key 멱등·{@code ex_dividend_date} round-trip·
 * DIVIDEND/SPLIT 공존을 커버하므로, 본 IT는 그와 중복을 피해 <b>국내 배당 수집 매핑 형태(shape)에 특화된 멱등</b>만 다룬다: (1) 확정 배당
 * 행(cash_amount·cash_rate·face_value·stock_kind·high_dividend_flag 모두 채움)이 {@code INSERT IGNORE}로
 * 멱등 적재되고 재실행 시 SQL 1142(grant 오류)나 행 수 증가 없이 원본 값을 보존하는지(AC-4), (2) {@code divi_kind}가 빈 문자열(ETF
 * 분배금 등, api-specs/kis/11 실측)인 경우 {@code event_subtype}이 NOT NULL·4컬럼 unique key 위반 없이 빈 문자열 그대로
 * 저장되는지(§엣지케이스)를 검증한다.
 *
 * <p>H2 미사용 — {@code INSERT IGNORE} 시맨틱(SQL 1142 grant 오류 재현·유니크 키 충돌 무시)은 MySQL에서만 보장된다. {@link
 * DividendRowAccumulator#buildRow} 매핑 규칙(현행 mapToEntity 이관분)과 정합한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("DividendScheduleRepository IT — 국내 배당 형태 멱등·divi_kind 빈 문자열 저장 (AC-4)")
@Tag("integration")
class DividendScheduleRepositoryIT {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    private static final AtomicInteger SYMBOL_SEQ = new AtomicInteger();

    /** 확정 국내 배당 event_subtype 원본값(divi_kind=결산배당). */
    private static final String SETTLEMENT_DIVIDEND = "결산배당";

    @Autowired private CorporateEventRepository corporateEventRepository;
    @Autowired private StockRepository stockRepository;

    /** 테스트 메서드 간 {@code uk_stocks_symbol_market} 충돌을 피하려 매 호출 유니크 국내 종목을 생성한다. */
    private Stock savedDomesticStock() {
        String symbol = "DV" + SYMBOL_SEQ.incrementAndGet();
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
     * 국내 배당 확정 행 매핑 형태 — {@link DividendRowAccumulator#buildRow}가 확정 행에 대해 생성하는 것과 동일한 필드 채움
     * (cash_amount/cash_rate/face_value/stock_kind/high_dividend_flag 모두 non-null).
     */
    private CorporateEvent confirmedDomesticDividend(
            Stock stock, LocalDate eventDate, String eventSubtype) {
        return CorporateEvent.builder()
                .stock(stock)
                .eventType(EventType.DIVIDEND)
                .eventDate(eventDate)
                .eventSubtype(eventSubtype)
                .payDate(LocalDate.of(2026, 8, 14))
                .cashAmount(new BigDecimal("361"))
                .currencyCode("KRW")
                .cashRate(new BigDecimal("0.5000"))
                .stockRate(new BigDecimal("0.0000"))
                .faceValue(100L)
                .stockKind("보통주")
                .highDividendFlag("N")
                .build();
    }

    @Nested
    @DisplayName("AC-4: 확정 배당 멱등 재실행 — 중복 미증가, SQL 1142 미발생")
    class ConfirmedDividendIdempotency {

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts") // 매핑 필드 전체 검증을 한 테스트에서 수행
        @DisplayName(
                "동일 (stock_id, DIVIDEND, event_date, event_subtype) 재INSERT — 행 수 불변, 예외(SQL 1142) 미발생, 원본 값 보존")
        void reinsertingSameConfirmedRowDoesNotDuplicateAndDoesNotThrow() {
            // Arrange — "확정 배당 이벤트가 이미 corporate_events에 저장돼 있다" (AC-4 Given)
            Stock stock = savedDomesticStock();
            LocalDate eventDate = LocalDate.of(2026, 6, 12);
            corporateEventRepository.insertIgnoreDuplicate(
                    confirmedDomesticDividend(stock, eventDate, SETTLEMENT_DIVIDEND));
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);

            // Act — "다음 날 배치가 동일 이벤트를 다시 조회·INSERT한다" (AC-4 When). SQL 1142(grant 오류) 미발생을 명시적으로
            // 단언한다.
            assertThatCode(
                            () ->
                                    corporateEventRepository.insertIgnoreDuplicate(
                                            confirmedDomesticDividend(
                                                    stock, eventDate, SETTLEMENT_DIVIDEND)))
                    .as("INSERT IGNORE 재실행은 SQL 1142(grant 오류) 등 어떤 예외도 던지지 않는다")
                    .doesNotThrowAnyException();

            // Assert — "INSERT IGNORE로 중복이 무시되어 행 수가 증가하지 않는다" (AC-4 Then)
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
            CorporateEvent saved = onlyRowOf(stock);
            assertThat(saved.getCashAmount()).isEqualByComparingTo("361");
            assertThat(saved.getCashRate()).isEqualByComparingTo("0.5000");
            assertThat(saved.getFaceValue()).isEqualTo(100L);
            assertThat(saved.getStockKind()).isEqualTo("보통주");
            assertThat(saved.getHighDividendFlag()).isEqualTo("N");
        }

        @Test
        @DisplayName("재INSERT 시 변경된 금액으로도 원본 값이 덮어써지지 않는다 (Tier-1 INSERT-only, UPDATE 없음)")
        void reinsertWithDifferentAmountPreservesOriginalValue() {
            // Arrange
            Stock stock = savedDomesticStock();
            LocalDate eventDate = LocalDate.of(2026, 6, 12);
            corporateEventRepository.insertIgnoreDuplicate(
                    confirmedDomesticDividend(stock, eventDate, SETTLEMENT_DIVIDEND));

            // Act — 동일 4컬럼 키, 다른 금액으로 재적재 시도
            CorporateEvent differentAmount =
                    CorporateEvent.builder()
                            .stock(stock)
                            .eventType(EventType.DIVIDEND)
                            .eventDate(eventDate)
                            .eventSubtype(SETTLEMENT_DIVIDEND)
                            .cashAmount(new BigDecimal("999999"))
                            .currencyCode("KRW")
                            .build();
            corporateEventRepository.insertIgnoreDuplicate(differentAmount);

            // Assert — 원본 361 그대로, UPDATE 발생 없음
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
            assertThat(onlyRowOf(stock).getCashAmount()).isEqualByComparingTo("361");
        }
    }

    @Nested
    @DisplayName("divi_kind 빈 문자열 → event_subtype 저장 (엣지 케이스, ETF 분배금)")
    class EmptyEventSubtype {

        @Test
        @DisplayName("event_subtype=\"\" (non-null) — NOT NULL·4컬럼 unique key 위반 없이 정상 저장")
        void emptyStringEventSubtypeStoredWithoutViolation() {
            // Arrange — ETF 분배금 등 divi_kind가 빈 문자열인 실측 케이스(api-specs/kis/11)
            Stock stock = savedDomesticStock();
            LocalDate eventDate = LocalDate.of(2026, 6, 12);

            // Act
            corporateEventRepository.insertIgnoreDuplicate(
                    confirmedDomesticDividend(stock, eventDate, ""));

            // Assert — 빈 문자열은 non-null이라 NOT NULL 제약·4컬럼 unique key 모두 무해
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
            assertThat(onlyRowOf(stock).getEventSubtype()).isEmpty();
        }

        @Test
        @DisplayName("event_subtype=\"\" 행과 event_subtype=\"결산배당\" 행 — 4컬럼 키가 달라 별도 공존")
        void emptyAndNonEmptySubtypeCoexistAsSeparateRows() {
            // Arrange
            Stock stock = savedDomesticStock();
            LocalDate eventDate = LocalDate.of(2026, 6, 12);
            corporateEventRepository.insertIgnoreDuplicate(
                    confirmedDomesticDividend(stock, eventDate, ""));

            // Act — 동일 종목·동일 event_date, event_subtype만 다름
            corporateEventRepository.insertIgnoreDuplicate(
                    confirmedDomesticDividend(stock, eventDate, SETTLEMENT_DIVIDEND));

            // Assert — event_subtype이 4컬럼 unique key의 일부이므로 별도 2행 공존
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(2L);
        }
    }

    private CorporateEvent onlyRowOf(Stock stock) {
        return corporateEventRepository.findAll().stream()
                .filter(e -> e.getStock().getId().equals(stock.getId()))
                .findFirst()
                .orElseThrow();
    }
}
