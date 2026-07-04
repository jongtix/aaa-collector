package com.aaa.collector.stock;

import com.aaa.collector.stock.enums.EventType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * 종목별 병렬 수집 중 배당 일정 1건 검증·매핑·미확정(0/0) defer 판정과 관측 카운터 집계를 전담하는 협력자
 * (SPEC-COLLECTOR-DIVIDEND-FIX-001 REQ-DIVFIX-020~022, 030~032).
 *
 * <p>{@link DividendScheduleCollectionService}가 {@code collect()} 1회 호출마다 새 인스턴스를 생성해 종목별 Virtual
 * Thread들과 공유한다. 카운터는 {@link AtomicInteger}로 다중 스레드 갱신에 안전하다. 형제 {@code
 * com.aaa.collector.stock.rights.OverseasRightsRowAccumulator}의 검증·매핑·카운팅 분리 패턴을 국내 배당에 이식해 서비스 클래스
 * 자체의 결합도(PMD CouplingBetweenObjects)를 낮춘다.
 *
 * <p><b>skip(defer) 판정(REQ-DIVFIX-020/030, RD-6)</b>: 매핑된 {@code cash_amount}·{@code cash_rate}가
 * <em>둘 다</em> 0이면 {@code record_date}(과거/미래) 무관하게 무조건 defer한다 — {@code corporate_events} 행을 생성하지
 * 않고 {@code skippedUnconfirmed}만 증가시킨다(Tier-1 INSERT-only라 확정 전에는 채울 수 없어 확정까지 미룬다). <b>rate-only 행
 * 저장(REQ-DIVFIX-032, RD-7)</b>: {@code cash_amount==0} AND {@code cash_rate!=0}인 행은 defer 대상이 아니며
 * 원본 그대로 저장한다 — {@code face_val × cash_rate / 100} 역산은 수행하지 않는다(#44/analyzer 위임).
 */
@Slf4j
final class DividendRowAccumulator {

    /** yyyyMMdd 정규화 후 기대 길이. */
    private static final int DATE_NORMALIZED_LENGTH = 8;

    /** DECIMAL(12,4) 최대 정수부 자릿수 (10^8 = 99999999). */
    private static final int MAX_DECIMAL_INTEGER_DIGITS = 8;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /** 국내 배당 통화 코드 고정값 (REQ-ODA-042). */
    private static final String DOMESTIC_CURRENCY_CODE = "KRW";

    /** REQ-BATCH3-070: record_date null/파싱 실패 등 검증 실패로 skip된 행 수. */
    private final AtomicInteger skippedValidationCount = new AtomicInteger();

    /** REQ-DIVFIX-020, RD-6: cash_amount·cash_rate 둘 다 0이라 defer(미생성)된 행 수. */
    private final AtomicInteger skippedUnconfirmedCount = new AtomicInteger();

    /**
     * 배당 일정 1건을 검증·매핑하여 배치에 추가한다(REQ-INSERT-011, REQ-DIVFIX-020~022, 030~032).
     *
     * <p>REQ-BATCH3-070: record_date null/파싱 실패는 검증 skip(skippedValidation). 매핑 성공 시 cash_amount·
     * cash_rate가 둘 다 0이면 record_date와 무관하게 무조건 defer(skippedUnconfirmed, RD-6). 확정 행과 rate-only 행
     * (RD-7)은 원본 그대로 배치에 추가한다.
     */
    void buildRow(
            KisDividendScheduleResponse.DividendRow row, Stock stock, List<CorporateEvent> batch) {
        CorporateEvent entity = mapToEntity(row, stock);
        if (entity == null) {
            skippedValidationCount.incrementAndGet();
            return;
        }

        // REQ-DIVFIX-020/030, RD-6: cash_amount·cash_rate 둘 다 0 → 날짜 무관 무조건 defer(미생성)
        if (isZero(entity.getCashAmount()) && isZero(entity.getCashRate())) {
            skippedUnconfirmedCount.incrementAndGet();
            return;
        }

        // REQ-DIVFIX-021/032, RD-7: 확정 행 + rate-only 행 모두 원본 그대로 저장(역산 없음)
        batch.add(entity);
    }

    /** null은 미확정 판정 대상이 아니다(값 부재 ≠ 명시적 0) — 파싱된 값이 명시적으로 0일 때만 true. */
    private boolean isZero(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) == 0;
    }

    int skippedValidation() {
        return skippedValidationCount.get();
    }

    int skippedUnconfirmed() {
        return skippedUnconfirmedCount.get();
    }

    /**
     * DividendRow → CorporateEvent 매핑.
     *
     * @return null 이면 검증 실패(skip)
     */
    @SuppressWarnings("PMD.GuardLogStatement") // debug 파라미터 구성 비용은 무시 가능
    private CorporateEvent mapToEntity(KisDividendScheduleResponse.DividendRow row, Stock stock) {
        // REQ-BATCH3-070: null record_date skip
        String recordDate = row.recordDate();
        if (recordDate == null || recordDate.isBlank()) {
            log.debug("[dividend] 검증 실패 (recordDate null) — sht_cd={}", row.shtCd());
            return null;
        }

        LocalDate eventDate;
        try {
            eventDate = LocalDate.parse(recordDate, DATE_FMT);
        } catch (DateTimeParseException e) {
            log.debug(
                    "[dividend] 날짜 파싱 실패 (recordDate={}) — sht_cd={}, error={}",
                    recordDate,
                    row.shtCd(),
                    e.getMessage());
            return null;
        }

        // 선택적 날짜 필드 파싱 (null 허용)
        LocalDate payDate = parseDateOrNull(row.diviPayDt(), row.shtCd(), "diviPayDt");
        LocalDate stockPayDate = parseDateOrNull(row.stkDivPayDt(), row.shtCd(), "stkDivPayDt");
        LocalDate oddPayDate = parseDateOrNull(row.oddPayDt(), row.shtCd(), "oddPayDt");

        // 금액 파싱 (null 허용) — V33: cash_amount는 BigDecimal(15,5)
        BigDecimal cashAmount = parseCashAmountOrNull(row.perStoDiviAmt());
        Long faceValue = parseLongOrNull(row.faceVal());

        // 비율 파싱 — DECIMAL(12,4) 경계 초과 시 skip
        BigDecimal cashRate = parseRateOrNull(row.diviRate(), "cash_rate", row.shtCd());
        BigDecimal stockRate = parseRateOrNull(row.stkDiviRate(), "stock_rate", row.shtCd());

        // REQ-BATCH3-054: EventType.DIVIDEND만 수집
        return CorporateEvent.builder()
                .stock(stock)
                .eventType(EventType.DIVIDEND)
                .eventDate(eventDate)
                .eventSubtype(row.diviKind())
                .payDate(payDate)
                .stockPayDate(stockPayDate)
                .oddPayDate(oddPayDate)
                .cashAmount(cashAmount)
                .currencyCode(DOMESTIC_CURRENCY_CODE)
                .cashRate(cashRate)
                .stockRate(stockRate)
                .faceValue(faceValue)
                .stockKind(row.stkKind())
                .highDividendFlag(row.highDiviGb())
                .build();
    }

    private LocalDate parseDateOrNull(String raw, String shtCd, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 날짜 형식: yyyyMMdd 또는 yyyy-MM-dd 등 가능 — 명세는 String(10) 이므로 슬래시 포함일 수 있음
        String normalized = raw.replace("-", "");
        if (normalized.length() != DATE_NORMALIZED_LENGTH) {
            return null;
        }
        try {
            return LocalDate.parse(normalized, DATE_FMT);
        } catch (DateTimeParseException e) {
            log.debug("[dividend] {} 파싱 실패 — sht_cd={}, value={}", fieldName, shtCd, raw);
            return null;
        }
    }

    private Long parseLongOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal bd = new BigDecimal(raw.trim());
            return bd.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /**
     * 현금배당금 파싱 — {@code cash_amount}는 V33부터 {@code DECIMAL(15,5)}(REQ-ODA-040).
     *
     * @return null이면 파싱 실패(값 미존재)
     */
    private BigDecimal parseCashAmountOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 비율 파싱 — DECIMAL(12,4) 범위 초과 시 null 반환 (REQ-BATCH3-070).
     *
     * <p>DECIMAL(12,4) 최대 정수부: 10^8 (99999999.9999). 일반 배당률은 이 범위를 초과하지 않음.
     */
    private BigDecimal parseRateOrNull(String raw, String fieldName, String shtCd) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal bd = new BigDecimal(raw.trim());
            // DECIMAL(12,4) scale 조정
            BigDecimal scaled = bd.setScale(4, RoundingMode.HALF_UP);
            // 정수부 8자리 초과 검사
            if (scaled.precision() - scaled.scale() > MAX_DECIMAL_INTEGER_DIGITS) {
                log.debug("[dividend] {} 경계 초과 — sht_cd={}, value={}", fieldName, shtCd, raw);
                return null;
            }
            return scaled;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
