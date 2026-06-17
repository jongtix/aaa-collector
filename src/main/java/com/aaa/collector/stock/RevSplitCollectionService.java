package com.aaa.collector.stock;

import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.stock.enums.EventType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 예탁원정보 액면교체일정 수집 서비스 (TR HHKDB669105C0, REQ-BATCH5-010~073).
 *
 * <p>단일키(isa) 단일 호출, {@code SHT_CD=공백}(전체 종목), {@code MARKET_GB=0}(전체), 단일 페이지(PROBE-1: CTS 연속조회
 * 구조적 불가 확정 — top-level cts 필드 없음, 동일 범위 재호출 시 동일 100건).
 *
 * <p>관심종목만 저장(REQ-BATCH5-050): {@code sht_cd}가 활성 관심종목에 없는 행은 건별 skip + 집계 카운트 로깅(개별 WARN 남발 금지).
 *
 * <p>skip 우선 + 분할/병합 분류(REQ-BATCH5-021/022, CR-01):
 *
 * <ol>
 *   <li>먼저 degenerate/무변동 skip 판정: {@code af<=0 OR bf<=0 OR af==bf} → {@code skippedValidation}
 *   <li>통과분만 {@code af<bf} → "분할", {@code af>bf} → "병합"으로 분류
 * </ol>
 *
 * <p>{@code stream:daily:complete} 미발행(REQ-BATCH5-012). 백필 미수행(REQ-BATCH5-013).
 *
 * <p>{@link DividendScheduleCollectionService} 패턴 답습.
 */
// @MX:ANCHOR: [AUTO] RevSplitCollectionService.collect — 액면교체 수집 진입점
// @MX:REASON: 스케줄러(MarketBatchScheduler.collectRevSplit)에서 직접 호출, fan_in >= 3 예상
// @MX:SPEC: SPEC-COLLECTOR-BATCH-005
@Slf4j
@Service
@RequiredArgsConstructor
public class RevSplitCollectionService {

    /** 최대 페이지 수 — CTS 구조적 불가로 실질적으로 1. 형식적 안전 상한(PROBE-1). */
    private static final int MAX_PAGES = 1;

    /** DECIMAL(12,4) 최대 정수부 자릿수 (10^8 = 99999999). */
    private static final int MAX_DECIMAL_INTEGER_DIGITS = 8;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TR_ID = "HHKDB669105C0";
    private static final String PATH = "/uapi/domestic-stock/v1/ksdinfo/rev-split";

    private final KisApiExecutor kisApiExecutor;
    private final StockRepository stockRepository;
    private final CorporateEventRepository corporateEventRepository;

    /**
     * 액면교체 일정 수집을 실행하고 집계 결과를 반환한다.
     *
     * <p>미래 윈도우 파라미터화: 스케줄러는 today-14/today+60을 전달하고, 향후 백필 마일스톤에서는 청크 범위를 전달할 수
     * 있다(REQ-BATCH5-013).
     *
     * @param from 조회 시작일 (F_DT)
     * @param to 조회 종료일 (T_DT)
     * @return attempted/succeeded/skippedNonWatchlist/skippedValidation 행 수 집계
     */
    public RevSplitCollectionResult collect(LocalDate from, LocalDate to) {
        // 관심종목 맵 — 심볼 기준 조회 (비관심종목 skip 판단용)
        Map<String, Stock> watchlistMap = buildWatchlistMap();

        int attempted = 0;
        int succeeded = 0;
        int skippedNonWatchlist = 0;
        int skippedValidation = 0;

        String fromDate = from.format(DATE_FMT);
        String toDate = to.format(DATE_FMT);

        int pageCount = 0;
        boolean capReached = false;

        while (pageCount < MAX_PAGES) {
            pageCount++;

            // REQ-BATCH5-011: SHT_CD=공백(전체), MARKET_GB=0(전체), F_DT/T_DT 윈도우
            KisRevSplitResponse response =
                    kisApiExecutor.executeGet(
                            uri ->
                                    uri.path(PATH)
                                            .queryParam("SHT_CD", "")
                                            .queryParam("MARKET_GB", "0")
                                            .queryParam("F_DT", fromDate)
                                            .queryParam("T_DT", toDate)
                                            .queryParam("CTS", "")
                                            .build(),
                            TR_ID,
                            KisRevSplitResponse.class);

            List<KisRevSplitResponse.RevSplitRow> rows = response.output1();

            // REQ-BATCH5-073: 빈 output1 → 0건 정상 종료
            if (rows.isEmpty()) {
                log.info("[rev-split] output1 빈 응답 — 단일 페이지 종료 (page={})", pageCount);
                break;
            }

            // PROBE-1: 100건 캡 도달 시 WARN (윈도우 축소 필요)
            if (rows.size() >= 100) {
                log.warn(
                        "[rev-split] 100건 캡 도달 (records={}) — 윈도우 F_DT/T_DT 축소 필요 (PROBE-1)",
                        rows.size());
                capReached = true;
            }

            RowCounts counts = processRows(rows, watchlistMap);
            attempted += counts.attempted;
            succeeded += counts.succeeded;
            skippedNonWatchlist += counts.skippedNonWatchlist;
            skippedValidation += counts.skippedValidation;

            // 단일 페이지 — 루프 종료 (CTS 구조적 불가, PROBE-1)
            break;
        }

        if (pageCount >= MAX_PAGES && !capReached) {
            log.warn("[rev-split] MAX_PAGES({}) 도달 — 강제 종료", MAX_PAGES);
        }

        if (skippedNonWatchlist > 0) {
            log.info("[rev-split] 비관심종목 skip 집계 — {} 건", skippedNonWatchlist);
        }

        RevSplitCollectionResult result =
                new RevSplitCollectionResult(
                        attempted, succeeded, skippedNonWatchlist, skippedValidation);
        log.info(
                "[rev-split] 수집 완료 — attempted={}, succeeded={}, skippedNonWatchlist={}, skippedValidation={}",
                result.attempted(),
                result.succeeded(),
                result.skippedNonWatchlist(),
                result.skippedValidation());
        return result;
    }

    private RowCounts processRows(
            List<KisRevSplitResponse.RevSplitRow> rows, Map<String, Stock> watchlistMap) {
        int attempted = 0;
        int succeeded = 0;
        int skippedNonWatchlist = 0;
        int skippedValidation = 0;

        for (KisRevSplitResponse.RevSplitRow row : rows) {
            attempted++;

            // 키 필드 검증
            String shtCd = row.shtCd();
            if (shtCd == null || shtCd.isBlank()) {
                skippedValidation++;
                log.debug("[rev-split] 검증 실패 (shtCd null/blank) — skip");
                continue;
            }

            // REQ-BATCH5-050: 관심종목이 아니면 skip (집계 카운트만, 개별 WARN 금지)
            Stock stock = watchlistMap.get(shtCd.trim());
            if (stock == null) {
                skippedNonWatchlist++;
                continue;
            }

            // REQ-BATCH5-021/022/070: 매핑 (skip 판정 먼저, 통과분만 분류)
            CorporateEvent entity = mapToEntity(row, stock);
            if (entity == null) {
                skippedValidation++;
                continue;
            }

            // REQ-BATCH5-024: uk_corporate_events 멱등 저장
            // @MX:WARN: [AUTO] 독성 행 예외 흡수 — 영구 정체 방지
            // @MX:REASON: insertIgnoreDuplicate 예외 시 다음 행 처리 보호
            try {
                corporateEventRepository.insertIgnoreDuplicate(entity);
                succeeded++;
            } catch (DataAccessException ex) {
                log.warn(
                        "[rev-split] 행 저장 실패 — skip (sht_cd={}, error={}: {})",
                        shtCd,
                        ex.getClass().getSimpleName(),
                        ex.getMessage());
                skippedValidation++;
            }
        }

        return new RowCounts(attempted, succeeded, skippedNonWatchlist, skippedValidation);
    }

    /** 활성 관심종목을 symbol → Stock 맵으로 구성. */
    @SuppressWarnings("PMD.UseConcurrentHashMap") // 단일 스레드 빌드 전용, 이후 읽기만 함
    private Map<String, Stock> buildWatchlistMap() {
        Map<String, Stock> map = new HashMap<>();
        for (Stock s : stockRepository.findAllActive()) {
            map.put(s.getSymbol(), s);
        }
        return map;
    }

    /**
     * RevSplitRow → CorporateEvent 매핑.
     *
     * <p>처리 순서: (1) 키/날짜 검증 → (2) 면액 파싱 → (3) degenerate/무변동 skip(CR-01) → (4) 분할/병합 분류 → (5) 비율
     * 산출.
     *
     * <p>[HARD] REQ-BATCH5-025: SPLIT 행 {@code stock_rate}는 V12 컬럼 주석상 "주식배당률"이 아니라 분할비율({@code
     * inter_bf_face_amt / inter_af_face_amt})이다. analyzer가 {@code event_type} 분기로 해석한다.
     *
     * @return null 이면 skip
     */
    @SuppressWarnings("PMD.GuardLogStatement") // debug 파라미터 구성 비용은 무시 가능
    private CorporateEvent mapToEntity(KisRevSplitResponse.RevSplitRow row, Stock stock) {
        // record_date 검증 및 파싱
        String recordDate = row.recordDate();
        if (recordDate == null || recordDate.isBlank()) {
            log.debug("[rev-split] 검증 실패 (recordDate null) — sht_cd={}", row.shtCd());
            return null;
        }

        LocalDate eventDate;
        try {
            eventDate = LocalDate.parse(recordDate, DATE_FMT);
        } catch (DateTimeParseException e) {
            log.debug(
                    "[rev-split] 날짜 파싱 실패 (recordDate={}) — sht_cd={}, error={}",
                    recordDate,
                    row.shtCd(),
                    e.getMessage());
            return null;
        }

        // 면액 파싱 (9자리 zero-pad 흡수 — parseLongOrNull)
        Long bf = parseLongOrNull(row.interBfFaceAmt());
        Long af = parseLongOrNull(row.interAfFaceAmt());
        if (bf == null || af == null) {
            log.debug(
                    "[rev-split] 면액 파싱 실패 — sht_cd={}, bf={}, af={}",
                    row.shtCd(),
                    row.interBfFaceAmt(),
                    row.interAfFaceAmt());
            return null;
        }

        // REQ-BATCH5-022, CR-01: skip 판정 먼저 (degenerate/무변동) — 분류 이전
        // af<=0 OR bf<=0 → 분모 0/음수 비율 산출 불가 OR 무액면전환 ratio=0 degenerate (실측 900250/900070)
        // af==bf → 동일면액 무조정
        if (af <= 0 || bf <= 0 || af.equals(bf)) {
            log.debug(
                    "[rev-split] degenerate/무변동 skip (bf={}, af={}) — sht_cd={}",
                    bf,
                    af,
                    row.shtCd());
            return null;
        }

        // REQ-BATCH5-021: 통과분만 분할(af<bf)/병합(af>bf) 분류
        String eventSubtype = af < bf ? "분할" : "병합";

        // REQ-BATCH5-023: 분할비율 = bf/af (분할>1·병합<1), scale 4·HALF_UP 필수
        // (선례 parseRateOrNull setScale(4,HALF_UP)·DECIMAL(12,4) 정합 — MA-01)
        // [HARD] REQ-BATCH5-025: SPLIT 행 stock_rate = 분할비율(bf/af), 주식배당률 아님
        BigDecimal stockRate = calcSplitRatio(bf, af, row.shtCd());
        if (stockRate == null) {
            return null; // DECIMAL(12,4) 정수부 경계 초과 → skip
        }

        // REQ-BATCH5-020: corporate_events 매핑
        // 미사용
        // 컬럼(pay_date/stock_pay_date/odd_pay_date/cash_amount/cash_rate/stock_kind/high_dividend_flag)은 null
        return CorporateEvent.builder()
                .stock(stock)
                .eventType(EventType.SPLIT)
                .eventDate(eventDate)
                .eventSubtype(eventSubtype)
                .faceValue(af) // inter_af_face_amt (교체 후 면액)
                .stockRate(stockRate) // 분할비율(bf/af) — 주식배당률 아님 (REQ-BATCH5-025)
                .build();
    }

    /**
     * 분할비율 산출: {@code bf.divide(af, 4, HALF_UP)}.
     *
     * <p>비종료 소수(예 100/300=0.333…) 처리: {@code RoundingMode.HALF_UP} 필수 — 미지정 시 {@code
     * ArithmeticException}(MA-01). 선례 {@code parseRateOrNull} {@code setScale(4, HALF_UP)} 정합.
     *
     * <p>DECIMAL(12,4) 정수부 경계({@code <10^8}) 초과 시 null 반환 → 행 skip(REQ-BATCH5-070).
     *
     * @return null 이면 skip
     */
    private BigDecimal calcSplitRatio(Long bf, Long af, String shtCd) {
        BigDecimal bfBd = BigDecimal.valueOf(bf);
        BigDecimal afBd = BigDecimal.valueOf(af);
        BigDecimal ratio = bfBd.divide(afBd, 4, RoundingMode.HALF_UP);
        // DECIMAL(12,4) 정수부 경계 검사 (선례 parseRateOrNull 패턴)
        if (ratio.precision() - ratio.scale() > MAX_DECIMAL_INTEGER_DIGITS) {
            log.debug(
                    "[rev-split] 분할비율 경계 초과 — sht_cd={}, bf={}, af={}, ratio={}",
                    shtCd,
                    bf,
                    af,
                    ratio);
            return null;
        }
        return ratio;
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

    /** 페이지 행 처리 결과 집계 (내부 전달용). */
    private record RowCounts(
            int attempted, int succeeded, int skippedNonWatchlist, int skippedValidation) {}
}
