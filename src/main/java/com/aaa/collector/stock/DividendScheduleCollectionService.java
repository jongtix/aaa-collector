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
import org.springframework.stereotype.Service;

/**
 * 배당 일정 수집 서비스 (TR HHKDB669102C0).
 *
 * <p>단일키(isa) 단일 호출, {@code SHT_CD=공백}(전체 종목), {@code GB1=0}(배당전체), CTS 페이징으로 전체 범위를 순회한다.
 * per-stock 루프 아님.
 *
 * <p>관심종목만 저장(REQ-BATCH3-052): {@code sht_cd}가 활성 관심종목에 없는 행은 건별 skip. 비관심종목 skip은 집계 카운트 위주 로깅(개별
 * WARN 남발 회피).
 *
 * <p>EventType.DIVIDEND만 수집 — RIGHTS_ISSUE 제외 (REQ-BATCH3-054).
 *
 * <p>stream:daily:complete 미발행(REQ-BATCH3-011). 백필 미수행(REQ-BATCH3-012).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendScheduleCollectionService {

    /** 최대 페이지 수 — 무한 루프 방지 안전 상한. */
    private static final int MAX_PAGES = 1000;

    /** yyyyMMdd 정규화 후 기대 길이. */
    private static final int DATE_NORMALIZED_LENGTH = 8;

    /** DECIMAL(12,4) 최대 정수부 자릿수 (10^8 = 99999999). */
    private static final int MAX_DECIMAL_INTEGER_DIGITS = 8;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TR_ID = "HHKDB669102C0";
    private static final String PATH = "/uapi/domestic-stock/v1/ksdinfo/dividend";

    private final KisApiExecutor kisApiExecutor;
    private final StockRepository stockRepository;
    private final CorporateEventRepository corporateEventRepository;

    /**
     * 배당 일정 수집을 실행하고 집계 결과를 반환한다.
     *
     * @param fromDate 조회 시작일 (yyyyMMdd)
     * @param toDate 조회 종료일 (yyyyMMdd)
     * @return attempted/succeeded/skipped 행 수 집계
     */
    public DividendCollectionResult collect(String fromDate, String toDate) {
        // 관심종목 맵 — 심볼 기준 조회 (비관심종목 skip 판단용)
        Map<String, Stock> watchlistMap = buildWatchlistMap();

        int attempted = 0;
        int succeeded = 0;
        int skippedNonWatchlist = 0;
        int skippedValidation = 0;

        String cts = "";
        int pageCount = 0;

        while (pageCount < MAX_PAGES) {
            pageCount++;

            // REQ-BATCH3-050: CTS 페이징, GB1=0(배당전체), SHT_CD=공백(전체)
            final String currentCts = cts;
            KisDividendScheduleResponse response =
                    kisApiExecutor.executeGet(
                            uri ->
                                    uri.path(PATH)
                                            .queryParam("CTS", currentCts)
                                            .queryParam("GB1", "0")
                                            .queryParam("F_DT", fromDate)
                                            .queryParam("T_DT", toDate)
                                            .queryParam("SHT_CD", "")
                                            .queryParam("HIGH_GB", "")
                                            .build(),
                            TR_ID,
                            KisDividendScheduleResponse.class);

            // REQ-BATCH3-073: 빈 output1 → 페이지 종료
            if (response.output1().isEmpty()) {
                log.info(
                        "[dividend] output1 빈 응답 — 페이지 종료 (page={}, cts={})",
                        pageCount,
                        currentCts);
                break;
            }

            RowCounts counts = processRows(response.output1(), watchlistMap);
            attempted += counts.attempted;
            succeeded += counts.succeeded;
            skippedNonWatchlist += counts.skippedNonWatchlist;
            skippedValidation += counts.skippedValidation;

            // CTS 페이징 종료 조건
            String nextCts = response.cts();
            if (nextCts == null || nextCts.isBlank()) {
                log.debug("[dividend] CTS 종료 — page={}", pageCount);
                break;
            }
            cts = nextCts;
        }

        if (pageCount >= MAX_PAGES) {
            log.warn("[dividend] 최대 페이지({}) 도달 — 강제 종료", MAX_PAGES);
        }

        if (skippedNonWatchlist > 0) {
            log.info("[dividend] 비관심종목 skip 집계 — {} 건", skippedNonWatchlist);
        }

        DividendCollectionResult result =
                new DividendCollectionResult(
                        attempted, succeeded, skippedNonWatchlist, skippedValidation);
        log.info(
                "[dividend] 수집 완료 — attempted={}, succeeded={}, skippedNonWatchlist={}, skippedValidation={}",
                result.attempted(),
                result.succeeded(),
                result.skippedNonWatchlist(),
                result.skippedValidation());
        return result;
    }

    private RowCounts processRows(
            List<KisDividendScheduleResponse.DividendRow> rows, Map<String, Stock> watchlistMap) {
        int attempted = 0;
        int succeeded = 0;
        int skippedNonWatchlist = 0;
        int skippedValidation = 0;

        for (KisDividendScheduleResponse.DividendRow row : rows) {
            attempted++;
            String shtCd = row.shtCd();
            if (shtCd == null || shtCd.isBlank()) {
                skippedValidation++;
                log.debug("[dividend] 검증 실패 (shtCd null) — skip");
                continue;
            }

            // REQ-BATCH3-052: 관심종목이 아니면 skip
            Stock stock = watchlistMap.get(shtCd.trim());
            if (stock == null) {
                skippedNonWatchlist++;
                continue;
            }

            // REQ-BATCH3-051: 필드 매핑
            CorporateEvent entity = mapToEntity(row, stock);
            if (entity == null) {
                skippedValidation++;
                continue;
            }

            // REQ-BATCH3-053: uk_corporate_events 멱등 저장
            corporateEventRepository.insertIgnoreDuplicate(entity);
            succeeded++;
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
     * DividendRow → CorporateEvent 매핑.
     *
     * @return null 이면 skip
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

        // 금액 파싱 (null 허용)
        Long cashAmount = parseLongOrNull(row.perStoDiviAmt());
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

    /** 페이지 행 처리 결과 집계 (내부 전달용). */
    private record RowCounts(
            int attempted, int succeeded, int skippedNonWatchlist, int skippedValidation) {}
}
