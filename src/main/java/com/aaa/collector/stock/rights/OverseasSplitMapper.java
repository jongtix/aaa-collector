package com.aaa.collector.stock.rights;

import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.EventType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 해외(미국) 액면분할·병합(CTRGT011R {@code RGHT_TYPE_CD ∈ {14, 15}}) 행 → {@link CorporateEvent} 매핑·dedup 협력자
 * (SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-020/021/029~033/040~043/052).
 *
 * <p>정기 수집과 종목지정 백필이 공유하는 단일 매핑 규약 원천이다 — 매핑 로직 중복 없음(REQ-OSPLIT-061). 상태를 갖지 않는 순수 협력자다.
 *
 * <p><b>필터(REQ-OSPLIT-020/021)</b>: {@code dfnt_yn=Y}(확정) AND {@code pdno}가 추적 종목 심볼 집합에 속하는 행만
 * 채택한다. 시장 필터는 <b>추적 심볼 집합 조인만으로</b> 수행하며 {@code prdt_type_cd}는 판정에 쓰지 않는다(RD-1).
 *
 * <p><b>dedup(REQ-OSPLIT-029/030/031/033, RD-5)</b>: 그룹핑 키 = CTRGT011R 응답 필드 중 {@code
 * bass_dt}·{@code acpl_bass_dt}를 제외한 나머지(특히 {@code stck_alct_rt})가 모두 동일한 행들의 집합. 같은 {@code pdno}라도
 * {@code stck_alct_rt}가 다르면(CRWD 별개 분할) 별개 그룹으로 보존한다. 각 그룹 내에서 주말 {@code bass_dt}는 이상치로 제외한 뒤 남은 평일
 * {@code bass_dt} 최댓값을 {@code event_date}로 채택한다(공식 거래 반영일 일치).
 *
 * <p><b>정규화(REQ-OSPLIT-040)</b>: {@code stock_rate = stck_alct_rt ÷ 100}(원배율, scale 4 HALF_UP).
 * {@code DECIMAL(12,4)} 정수부 경계({@code 10^8}) 초과 시 행 skip(REQ-OSPLIT-043). {@code
 * cash_amount}/{@code cash_rate}/{@code currency_code}/{@code face_value}는 항상 null(REQ-OSPLIT-042).
 */
@Component
class OverseasSplitMapper {

    /** 권리유형코드 — 액면분할(REQ-OSPLIT-041). */
    static final String RGHT_TYPE_SPLIT = "14";

    /** 권리유형코드 — 액면병합(리버스 스플릿, REQ-OSPLIT-041). */
    static final String RGHT_TYPE_MERGE = "15";

    /** event_subtype 어휘 — 국내 {@code RevSplitCollectionService}와 공유(REQ-OSPLIT-041). */
    private static final String SUBTYPE_SPLIT = "분할";

    private static final String SUBTYPE_MERGE = "병합";

    private static final String DFNT_YN_CONFIRMED = "Y";

    /** {@code stock_rate} 스케일 — DECIMAL(12,4), 국내 SPLIT과 동일(REQ-OSPLIT-040). */
    private static final int RATE_SCALE = 4;

    /** DECIMAL(12,4) 최대 정수부 자릿수 (10^8 = 99999999, REQ-OSPLIT-043). */
    private static final int MAX_RATE_INTEGER_DIGITS = 8;

    /** {@code stck_alct_rt} ÷ 100 정규화 제수(REQ-OSPLIT-040). */
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 한 {@code RGHT_TYPE_CD}(14 또는 15)의 행 목록을 dedup·정규화해 {@link CorporateEvent} 목록으로 매핑한다.
     *
     * @param rows CTRGT011R {@code output}의 원본 행(단일 유형)
     * @param rghtTypeCd 요청 권리유형코드(14=분할, 15=병합) — {@code event_subtype} 결정
     * @param trackedStockBySymbol 추적 종목 {@code symbol → Stock} 맵. keySet이 곧 추적 심볼
     *     집합(REQ-OSPLIT-021)
     * @return 매핑된 이벤트 목록 + skip 집계
     */
    MapResult mapRows(
            List<KisPeriodRightsResponse.PeriodRightsRow> rows,
            String rghtTypeCd,
            Map<String, Stock> trackedStockBySymbol) {

        String eventSubtype = RGHT_TYPE_MERGE.equals(rghtTypeCd) ? SUBTYPE_MERGE : SUBTYPE_SPLIT;

        // REQ-OSPLIT-029: 그룹핑 키 = (bass_dt·acpl_bass_dt 제외 필드) → 그룹별 평일 bass_dt 목록
        Map<EventIdentityKey, List<LocalDate>> groups = new LinkedHashMap<>();
        int skippedUnconfirmed = 0;
        int skippedUntracked = 0;
        int skippedUnparsableDate = 0;

        for (KisPeriodRightsResponse.PeriodRightsRow row : rows) {
            // REQ-OSPLIT-020: 확정(dfnt_yn=Y)만 채택
            if (!DFNT_YN_CONFIRMED.equals(row.dfntYn())) {
                skippedUnconfirmed++;
                continue;
            }
            // REQ-OSPLIT-020/021: 추적 심볼 집합 조인만으로 시장 필터(prdt_type_cd 미사용)
            String symbol = row.pdno();
            if (symbol == null || !trackedStockBySymbol.containsKey(symbol)) {
                skippedUntracked++;
                continue;
            }
            // REQ-OSPLIT-031: event_date 산출을 위해 bass_dt(KST 기준일자) 파싱 필요
            LocalDate bassDt = parseDateOrNull(row.bassDt());
            if (bassDt == null) {
                skippedUnparsableDate++;
                continue;
            }
            groups.computeIfAbsent(identityKey(row), k -> new ArrayList<>()).add(bassDt);
        }

        List<CorporateEvent> events = new ArrayList<>();
        int skippedNoWeekday = 0;
        int skippedInvalidRate = 0;

        for (Map.Entry<EventIdentityKey, List<LocalDate>> entry : groups.entrySet()) {
            EventIdentityKey key = entry.getKey();
            // REQ-OSPLIT-030: 그룹 내 주말 bass_dt 이상치 제외 후 최댓값(REQ-OSPLIT-031)
            LocalDate eventDate =
                    entry.getValue().stream()
                            .filter(OverseasSplitMapper::isWeekday)
                            .max(LocalDate::compareTo)
                            .orElse(null);
            if (eventDate == null) {
                // REQ-OSPLIT-032: 유효 평일 bass_dt 없음 → 이벤트 skip
                skippedNoWeekday++;
                continue;
            }
            // REQ-OSPLIT-040/043: stck_alct_rt ÷ 100 정규화 + 경계 가드
            BigDecimal stockRate = normalizeStockRate(key.stckAlctRt());
            if (stockRate == null) {
                skippedInvalidRate++;
                continue;
            }
            Stock stock = trackedStockBySymbol.get(key.pdno());
            events.add(
                    CorporateEvent.builder()
                            .stock(stock)
                            .eventType(EventType.SPLIT)
                            .eventDate(eventDate)
                            .eventSubtype(eventSubtype)
                            .stockRate(stockRate)
                            .build());
        }

        return new MapResult(
                events,
                skippedUnconfirmed,
                skippedUntracked,
                skippedUnparsableDate,
                skippedNoWeekday,
                skippedInvalidRate);
    }

    /** {@code bass_dt}·{@code acpl_bass_dt}를 제외한 이벤트 동일성 필드로 그룹핑 키를 구성한다(REQ-OSPLIT-029). */
    private EventIdentityKey identityKey(KisPeriodRightsResponse.PeriodRightsRow row) {
        return new EventIdentityKey(
                row.pdno(),
                row.rghtTypeCd(),
                row.prdtName(),
                row.prdtTypeCd(),
                row.stdPdno(),
                row.sbscStrtDt(),
                row.sbscEndDt(),
                row.cashAlctRt(),
                row.stckAlctRt(),
                row.crcyCd(),
                row.crcyCd2(),
                row.crcyCd3(),
                row.crcyCd4(),
                row.alctFrcrUnpr(),
                row.stkpDvdnFrcrAmt2(),
                row.stkpDvdnFrcrAmt3(),
                row.stkpDvdnFrcrAmt4(),
                row.dfntYn());
    }

    /**
     * {@code stck_alct_rt}(배율×100) → {@code stock_rate}(원배율, scale 4 HALF_UP)로 정규화한다.
     *
     * <p>예: {@code 400.0} → {@code 4.0000}, {@code 12.5} → {@code 0.1250}. 파싱 실패 또는 DECIMAL(12,4)
     * 정수부 경계(10^8) 초과 시 null 반환 → 행 skip(REQ-OSPLIT-043).
     *
     * @return 정규화된 배율, 파싱 실패·경계 초과 시 null
     */
    private BigDecimal normalizeStockRate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal normalized =
                    new BigDecimal(raw.trim()).divide(HUNDRED, RATE_SCALE, RoundingMode.HALF_UP);
            if (normalized.precision() - normalized.scale() > MAX_RATE_INTEGER_DIGITS) {
                return null;
            }
            return normalized;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static boolean isWeekday(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /** yyyyMMdd 날짜 파싱 — null/공백/형식 오류 시 null. */
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

    /**
     * dedup "동일 이벤트" 그룹핑 키(REQ-OSPLIT-029) — CTRGT011R 응답 필드 중 {@code bass_dt}·{@code
     * acpl_bass_dt}를 제외한 나머지. {@code stck_alct_rt}가 포함되므로 같은 {@code pdno}라도 서로 다른 분할 비율은 별개 그룹으로
     * 보존된다(REQ-OSPLIT-033).
     */
    private record EventIdentityKey(
            String pdno,
            String rghtTypeCd,
            String prdtName,
            String prdtTypeCd,
            String stdPdno,
            String sbscStrtDt,
            String sbscEndDt,
            String cashAlctRt,
            String stckAlctRt,
            String crcyCd,
            String crcyCd2,
            String crcyCd3,
            String crcyCd4,
            String alctFrcrUnpr,
            String stkpDvdnFrcrAmt2,
            String stkpDvdnFrcrAmt3,
            String stkpDvdnFrcrAmt4,
            String dfntYn) {}

    /**
     * 매핑 결과 — 이벤트 목록 + skip 집계.
     *
     * @param events 매핑된 SPLIT 이벤트
     * @param skippedUnconfirmed 미확정(dfnt_yn≠Y) skip 행 수
     * @param skippedUntracked 비추적 종목 skip 행 수
     * @param skippedUnparsableDate bass_dt 파싱 실패 skip 행 수
     * @param skippedNoWeekday 유효 평일 bass_dt 부재로 skip된 이벤트 수(REQ-OSPLIT-032)
     * @param skippedInvalidRate stck_alct_rt 파싱 실패·경계 초과로 skip된 이벤트 수(REQ-OSPLIT-043)
     */
    record MapResult(
            List<CorporateEvent> events,
            int skippedUnconfirmed,
            int skippedUntracked,
            int skippedUnparsableDate,
            int skippedNoWeekday,
            int skippedInvalidRate) {

        MapResult {
            events = List.copyOf(events);
        }
    }
}
