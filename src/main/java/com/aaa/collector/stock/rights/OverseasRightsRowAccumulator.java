package com.aaa.collector.stock.rights;

import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.EventType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * 종목별 병렬 수집 중 현금배당 1건 검증·매핑과 관측 카운터 집계를 전담하는 협력자 (SPEC-COLLECTOR-OVERSEAS-ETC-001,
 * SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001 REQ-ODA-020~022, 070~072).
 *
 * <p>{@link OverseasRightsCollectionService}가 {@code collect()} 1회 호출마다 새 인스턴스를 생성해 종목별 Virtual
 * Thread들과 공유한다. 카운터는 {@link AtomicInteger}로 다중 스레드 갱신에 안전하다. 이 협력자로 매핑·카운팅 책임을 분리해 서비스 클래스 자체의
 * 결합도(PMD CouplingBetweenObjects)를 낮춘다.
 */
@Slf4j
final class OverseasRightsRowAccumulator {

    private static final String EVENT_SUBTYPE_GENERAL = "일반배당";
    private static final String EVENT_SUBTYPE_SPECIAL = "특별배당";

    /** 권리유형 현금배당 판정 값(REQ-OVE-023, RD-2). */
    private static final String CASH_DIVIDEND_TITLE = "현금배당";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final AtomicInteger succeededRows = new AtomicInteger();
    private final AtomicInteger skippedStocks = new AtomicInteger();
    private final AtomicInteger skippedNonCashRows = new AtomicInteger();
    private final AtomicInteger skippedValidationRows = new AtomicInteger();
    // W1: DB 독성 행(DataAccessException 흡수)을 검증 skip과 분리 집계 — 42,460-failure 사건군 관측성 보존
    private final AtomicInteger skippedToxicRows = new AtomicInteger();
    // REQ-ODA-022: 금액 맵 미확정 매칭으로 defer된 행 수(D5 — 프리페치 유형 폐기 defer는 이중 계상 안 함)
    private final AtomicInteger skippedUnconfirmed = new AtomicInteger();
    // aaa-infra#64, REQ-ODA-072: 74(스크립배당) 확정 매칭으로 defer된 행 수(skippedUnconfirmed와 분리 — 영구 defer)
    private final AtomicInteger skippedScripDividend = new AtomicInteger();

    void recordStockSkip() {
        skippedStocks.incrementAndGet();
    }

    void recordToxicRow() {
        skippedToxicRows.incrementAndGet();
    }

    void recordSucceededRows(int count) {
        succeededRows.addAndGet(count);
    }

    /**
     * 권리 1건을 검증·매핑하여 배치에 추가한다 (REQ-INSERT-011, REQ-ODA-020~022, 070~072).
     *
     * <p>비현금배당 skip(REQ-OVE-023a). 현금배당 행은 금액 맵을 {@code (symbol, record_dt)}로 조회해 리스트의 항목마다 1행을
     * 생성한다(경로 A, REQ-ODA-021 — 동일일자 03+75 병존 시 SUM 금지·별도 2행). 맵에 확정 매칭이 없으면, 스크립배당(74)으로 확정 관측된 키인지
     * 먼저 확인해 skippedScripDividend로 별도 집계하고(aaa-infra#64 — 영구 defer, 다음 배치에도 03/75로 확정될 수 없음), 그렇지
     * 않으면 기존 skippedUnconfirmed로 집계한다(REQ-ODA-022).
     */
    @SuppressWarnings("PMD.GuardLogStatement") // debug 파라미터 구성 비용은 무시 가능
    void buildRow(
            KisOverseasRightsResponse.RightsRow row,
            Stock stock,
            DividendAmountPrefetch prefetch,
            List<CorporateEvent> batch) {
        // REQ-OVE-023a: 비현금배당(증자·상폐 등) 행은 저장하지 않고 skip
        if (!CASH_DIVIDEND_TITLE.equals(row.caTitle())) {
            skippedNonCashRows.incrementAndGet();
            return;
        }

        // REQ-OVE-025/070: 필수 기준일(record_dt) null/공백/파싱 실패 → skip
        LocalDate eventDate = parseDateOrNull(row.recordDt());
        if (eventDate == null) {
            log.debug(
                    "[overseas-rights] 검증 실패 (record_dt={}) — symbol={}",
                    row.recordDt(),
                    stock.getSymbol());
            skippedValidationRows.incrementAndGet();
            return;
        }

        // REQ-ODA-020: 맵 키 날짜는 acpl_bass_dt이며 record_dt와 등가(§1.4-1, AC-9) — 직접 매칭
        DividendAmountKey key = new DividendAmountKey(stock.getSymbol(), eventDate);
        List<DividendAmountItem> items = prefetch.amountsByKey().getOrDefault(key, List.of());

        if (items.isEmpty()) {
            // aaa-infra#64, REQ-ODA-072: 74(스크립배당)로 확정 매칭된 키는 영원히 03/75로 확정될 수 없으므로
            // skippedUnconfirmed("곧 확정될 것") 카운터를 오염시키지 않도록 별도 집계한다.
            if (prefetch.scripDividendDates().contains(key)) {
                skippedScripDividend.incrementAndGet();
                return;
            }
            // REQ-ODA-022: 확정 매칭 없음(예정 또는 CTRGT011R 미반환) → 행 생성 자체를 defer.
            // D5: 프리페치 유형 폐기(prefetchTruncated/prefetchFailed)로 맵이 불완전한 배치에서는 어떤 미스가
            // 유형 폐기 때문인지 개별 판별 불가하므로, 그 배치 전체에서 행별 skippedUnconfirmed 이중 집계를
            // 억제한다(유형 카운터로만 관측). degraded=false(양 유형 모두 완주)일 때만 순수 미확정으로 집계한다.
            if (!prefetch.degraded()) {
                skippedUnconfirmed.incrementAndGet();
            }
            return;
        }

        LocalDate exDividendDate = parseDateOrNull(row.divLockDt());
        LocalDate payDate = parseDateOrNull(row.payDt());

        for (DividendAmountItem item : items) {
            batch.add(mapToEntity(stock, eventDate, exDividendDate, payDate, item));
        }
    }

    /** 확정 금액항목 1건 → CorporateEvent 매핑(REQ-ODA-021, 043, 046). */
    private CorporateEvent mapToEntity(
            Stock stock,
            LocalDate eventDate,
            LocalDate exDividendDate,
            LocalDate payDate,
            DividendAmountItem item) {
        return CorporateEvent.builder()
                .stock(stock)
                .eventType(EventType.DIVIDEND)
                .eventDate(eventDate)
                .exDividendDate(exDividendDate)
                .eventSubtype(eventSubtypeOf(item.rghtTypeCd()))
                .payDate(payDate)
                .cashAmount(item.cashAmount())
                .currencyCode(item.currencyCode())
                .cashRate(item.cashRate())
                .stockRate(item.stockRate())
                .build();
    }

    /** REQ-ODA-046: rght_type_cd → event_subtype 라벨. */
    private String eventSubtypeOf(String rghtTypeCd) {
        return DividendAmountPrefetcher.RIGHT_TYPE_SPECIAL.equals(rghtTypeCd)
                ? EVENT_SUBTYPE_SPECIAL
                : EVENT_SUBTYPE_GENERAL;
    }

    /** yyyyMMdd 날짜 파싱 — null/공백/형식 오류 시 null 반환. */
    private LocalDate parseDateOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 최종 집계 결과를 조립한다. */
    OverseasRightsCollectionResult toResult(int attemptedStocks, DividendAmountPrefetch prefetch) {
        return new OverseasRightsCollectionResult(
                attemptedStocks,
                succeededRows.get(),
                skippedStocks.get(),
                skippedNonCashRows.get(),
                skippedValidationRows.get(),
                skippedToxicRows.get(),
                skippedUnconfirmed.get(),
                skippedScripDividend.get(),
                prefetch.prefetchTruncated(),
                prefetch.prefetchFailed());
    }
}
