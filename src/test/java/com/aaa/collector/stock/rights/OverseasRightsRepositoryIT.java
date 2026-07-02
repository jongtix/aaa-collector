package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 해외 현금배당 → corporate_events 적재 IT (Testcontainers MySQL {@code mysql:8.4}).
 *
 * <p><b>범위 한정(orchestrator NEW-2 중복 회피)</b>: {@link
 * com.aaa.collector.stock.CorporateEventRepositoryTest}가 이미 {@code ex_dividend_date} 라운드트립· 제네릭
 * 멱등·DIVIDEND/SPLIT 공존을 커버하므로, 본 IT는 그와 중복을 피해 <b>해외 현금배당 매핑 형태(shape)에 특화된 멱등</b>만 다룬다: (1) 해외 행
 * 형태({@code record_dt}→{@code event_date}, {@code div_lock_dt}→{@code ex_dividend_date}, 금액 컬럼 NULL
 * — S-2)가 실 MySQL에 멱등 적재되고 {@code cash_amount}/{@code cash_rate}가 NULL로 남는지, (2) 동일 종목·동일 {@code
 * record_dt}에 event_subtype이 다른 현금배당(예: 정기·특별)이 몰릴 때 V33 4컬럼 unique key(경로 A, REQ-ODA-045)에서 별도 행으로
 * 공존하고, event_subtype까지 동일한 재적재만 {@code INSERT IGNORE}로 silent drop되는지(AC-1d).
 *
 * <p>H2 미사용 — INSERT IGNORE 시맨틱은 MySQL에서만 보장된다. {@link OverseasRightsCollectionService} 매핑 규칙과
 * 정합한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("OverseasRightsRepository IT — 해외 현금배당 형태 멱등·4컬럼 unique key 공존/충돌(경로 A)")
class OverseasRightsRepositoryIT {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    private static final AtomicInteger SYMBOL_SEQ = new AtomicInteger();

    /** {@link OverseasRightsCollectionService}가 현금배당 행에 매핑하는 event_subtype 원본값(ca_title). */
    private static final String CASH_DIVIDEND = "현금배당";

    @Autowired private CorporateEventRepository corporateEventRepository;
    @Autowired private StockRepository stockRepository;

    /** 테스트 메서드 간 {@code uk_stocks_symbol_market} 충돌을 피하려 매 호출 유니크 미국 종목을 생성한다. */
    private Stock savedUsStock() {
        String symbol = "US" + SYMBOL_SEQ.incrementAndGet();
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build());
    }

    /**
     * 해외 현금배당 매핑 형태의 CorporateEvent — record_dt→event_date, div_lock_dt→ex_dividend_date, 현금배당
     * eventSubtype, 금액 컬럼은 rights_by_ice 미반환이라 NULL(S-2).
     */
    private CorporateEvent overseasCashDividend(
            Stock stock, LocalDate eventDate, LocalDate exDividendDate, String subtype) {
        return CorporateEvent.builder()
                .stock(stock)
                .eventType(EventType.DIVIDEND)
                .eventDate(eventDate)
                .exDividendDate(exDividendDate)
                .eventSubtype(subtype)
                .payDate(LocalDate.of(2026, 5, 14))
                .build();
    }

    @Nested
    @DisplayName("해외 현금배당 형태 — 금액 컬럼 NULL 멱등 적재 (S-2)")
    class CashDividendShape {

        @Test
        @DisplayName("div_lock_dt→ex_dividend_date 채움, cash_amount/cash_rate는 NULL 유지, 동일 키 재적재 멱등")
        void overseasShapeIdempotentWithNullCashColumns() {
            // Arrange
            Stock stock = savedUsStock();
            LocalDate eventDate = LocalDate.of(2026, 5, 11);
            LocalDate exDividendDate = LocalDate.of(2026, 5, 9);
            corporateEventRepository.insertIgnoreDuplicate(
                    overseasCashDividend(stock, eventDate, exDividendDate, CASH_DIVIDEND));

            // Act — 동일 (stock_id, DIVIDEND, event_date) 재적재 (10분 cron 재실행 시나리오)
            corporateEventRepository.insertIgnoreDuplicate(
                    overseasCashDividend(stock, eventDate, exDividendDate, CASH_DIVIDEND));

            // Assert — 행 수 불변 + 해외 형태(ex_dividend_date 채움, 금액 컬럼 NULL) 보존
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
            CorporateEvent saved = onlyRowOf(stock);
            assertThat(saved.getExDividendDate()).isEqualTo(exDividendDate);
            assertThat(saved.getEventSubtype()).isEqualTo(CASH_DIVIDEND);
            assertThat(saved.getCashAmount()).isNull();
            assertThat(saved.getCashRate()).isNull();
        }
    }

    @Nested
    @DisplayName(
            "동일 record_dt 복수 현금배당 — event_subtype 다르면 별도 행 공존, 같으면 충돌 (경로 A, REQ-ODA-045, AC-1d)")
    class SameRecordDateCollision {

        @Test
        @DisplayName(
                "경로 A(V33 4컬럼 unique key): 특별+정기 현금배당이 같은 record_dt라도 event_subtype이 다르면 별도 2행 공존")
        void specialAndRegularSameRecordDateCoexistAsSeparateRows() {
            // Arrange — 첫 현금배당(정기): record_dt 2026-05-11, ex_dividend_date 2026-05-09
            Stock stock = savedUsStock();
            LocalDate eventDate = LocalDate.of(2026, 5, 11);
            LocalDate firstExDividend = LocalDate.of(2026, 5, 9);
            corporateEventRepository.insertIgnoreDuplicate(
                    overseasCashDividend(stock, eventDate, firstExDividend, CASH_DIVIDEND));

            // Act — 동일 record_dt의 특별 현금배당(다른 subtype·다른 ex_dividend_date)
            corporateEventRepository.insertIgnoreDuplicate(
                    overseasCashDividend(stock, eventDate, LocalDate.of(2026, 5, 10), "특별현금배당"));

            // Assert — 4컬럼 unique key(+event_subtype)에서 서로 다른 값이라 uk 충돌 없이 별도 2행 보존(경로 A)
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(2L);
        }

        @Test
        @DisplayName("동일 record_dt·동일 event_subtype 재적재는 여전히 uk 충돌로 silent drop(멱등 유지)")
        void sameSubtypeSameRecordDateStillCollides() {
            // Arrange
            Stock stock = savedUsStock();
            LocalDate eventDate = LocalDate.of(2026, 5, 11);
            LocalDate firstExDividend = LocalDate.of(2026, 5, 9);
            corporateEventRepository.insertIgnoreDuplicate(
                    overseasCashDividend(stock, eventDate, firstExDividend, CASH_DIVIDEND));

            // Act — 동일 (stock_id, DIVIDEND, event_date, event_subtype) 재적재
            corporateEventRepository.insertIgnoreDuplicate(
                    overseasCashDividend(
                            stock, eventDate, LocalDate.of(2026, 5, 10), CASH_DIVIDEND));

            // Assert — 동일 4컬럼 키라 두 번째 행 silent drop, 첫 행 값 보존
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
            CorporateEvent saved = onlyRowOf(stock);
            assertThat(saved.getExDividendDate()).isEqualTo(firstExDividend);
            assertThat(saved.getEventSubtype()).isEqualTo(CASH_DIVIDEND);
        }
    }

    private CorporateEvent onlyRowOf(Stock stock) {
        return corporateEventRepository.findAll().stream()
                .filter(e -> e.getStock().getId().equals(stock.getId()))
                .findFirst()
                .orElseThrow();
    }
}
