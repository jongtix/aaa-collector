package com.aaa.collector.market;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.daily.ParsedOhlcvRow;
import com.aaa.collector.stock.daily.WarningCountingOhlcvInserter;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 국내 종합지수(KOSPI·KOSDAQ) 일봉 수집 서비스 (TR FHKUP03500100).
 *
 * <p>watchlist sync가 등록한 INDEX 행({@code asset_type=INDEX, market=KRX, symbol IN ('0001','1001')})을
 * 조회하여 U 전용 API({@code inquire-daily-indexchartprice})로 일봉을 수집하고 {@code daily_ohlcv}에 멱등 저장한다.
 *
 * <p>호출은 단일 보호 게이트 {@link GuardedKisExecutor}를 경유한다(SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001) —
 * 기존 3-arg 맨몸 호출(throttle❌·재시도❌·isa 단일키)에서 throttle✅·재시도✅·멀티키 lease✅로 바뀐 의도된 동작 변경(패턴 A). 2개 종목 순회
 * 전체 = 1 batch이므로 루프 전 {@link LeaseSession}을 1회 연다(REQ-KISGATE-006a). 종목별 게이트 예외(재시도 소진 포함)는 기존
 * per-symbol graceful skip이 흡수하므로 패턴 A 종단(예외 전파)이 종목 단위 skip으로 흡수된다(보존, REQ-KISGATE-022). 전 키 사망 시도
 * 동일 세션이므로 두 종목 모두 graceful skip 처리된다.
 *
 * <p>신규 stocks 등록 없음(CR-02). J 주식 API 사용 금지(REQ-BATCH3-021b — 지수코드에 빈 output2).
 * stream:daily:complete 미발행(REQ-BATCH3-011). 백필 미수행(REQ-BATCH3-012).
 *
 * <p>검증(REQ-BATCH3-070/073): null 키 필드·종가 ≤ 0 건별 skip + 로그 + 계속. 빈 output2 → 0건 성공. 대상 INDEX 행 부재 →
 * skip + 로그.
 *
 * <p>REQ-INSERT-006: 종목별로 유효 행을 누적한 뒤 {@link WarningCountingOhlcvInserter#insertBatch(Long, List)}로
 * 단일 커넥션 배치 INSERT IGNORE 수행 (W-2 불변식).
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 국내 종합지수 일봉 수집 진입점 — U 전용 API·INDEX 조회·daily_ohlcv 멱등 저장·skip 집계
// @MX:REASON: SPEC-COLLECTOR-BATCH-003 REQ-BATCH3-020~023 — MarketBatchScheduler에서 호출
// @MX:SPEC: SPEC-COLLECTOR-BATCH-003
public class SectorIndexCollectionService {

    /** KOSPI 종합 업종코드 (REQ-BATCH3-021a). */
    static final String KOSPI_ISCD = "0001";

    /** KOSDAQ 종합 업종코드 (REQ-BATCH3-021a). */
    static final String KOSDAQ_ISCD = "1001";

    /** 수집 대상 업종코드 목록 (국내 종합지수 2종). */
    private static final List<String> TARGET_SYMBOLS = List.of(KOSPI_ISCD, KOSDAQ_ISCD);

    /** 일봉 조회 윈도우 캘린더 일수 (연휴 대비 14일). */
    private static final int LOOKBACK_CALENDAR_DAYS = 14;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TR_ID = "FHKUP03500100";
    private static final String PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-daily-indexchartprice";

    private final StockRepository stockRepository;
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final WarningCountingOhlcvInserter ohlcvInserter;

    /**
     * 국내 종합지수 일봉 수집을 실행하고 집계 결과를 반환한다.
     *
     * @param today 수집 기준일
     * @return attempted/succeeded/skipped 집계 (종목 단위)
     */
    @SuppressWarnings(
            "PMD.AvoidCatchingGenericException") // 종목별 예외 포착 — 다음 종목 계속 처리 (REQ-BATCH3-073)
    public SectorIndexCollectionResult collect(LocalDate today) {
        // Arrange: 기존 INDEX 행 조회 (BATCH-003 신규 등록 없음 — CR-02)
        List<Stock> indexStocks =
                stockRepository.findActiveIndexByMarketAndSymbolIn(Market.KRX, TARGET_SYMBOLS);

        Map<String, Stock> symbolToStock =
                indexStocks.stream()
                        .collect(Collectors.toMap(Stock::getSymbol, Function.identity()));

        // REQ-KISGATE-006a: 종목 순회 전체 = 1 batch — 루프 전 per-batch 헬스 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();

        int attempted = 0;
        int succeeded = 0;
        int skipped = 0;

        for (String symbol : TARGET_SYMBOLS) {
            attempted++;
            Stock stock = symbolToStock.get(symbol);

            if (stock == null) {
                // REQ-BATCH3-021: INDEX 행 부재 시 skip + 로그 (등록은 watchlist sync 책임)
                log.warn(
                        "[sector-index] INDEX 행 부재 — skip (등록은 watchlist sync 책임). symbol={}",
                        symbol);
                skipped++;
                continue;
            }

            try {
                int saved = collectSymbol(stock, symbol, session, today);
                succeeded++;
                log.info("[sector-index] 수집 완료 — symbol={}, saved={}", symbol, saved);
            } catch (InterruptedException e) {
                // 인터럽트 플래그 복원 후 종목 graceful skip (REQ-KISGATE-022 보존, 전파 아님)
                Thread.currentThread().interrupt();
                log.error("[sector-index] 인터럽트 — skip. symbol={}", symbol);
                skipped++;
            } catch (Exception e) {
                // REQ-KISGATE-022 보존: 게이트 재시도 소진·전 키 사망(NoHealthyKeyException) 등 모든 종목별 예외를
                // graceful skip으로 흡수한다(패턴 A 종단 = 예외 전파가 종목 단위 skip으로 흡수됨).
                log.error(
                        "[sector-index] 수집 예외 — skip. symbol={}, error={}", symbol, e.getMessage());
                skipped++;
            }
        }

        SectorIndexCollectionResult result =
                new SectorIndexCollectionResult(attempted, succeeded, skipped);
        log.info(
                "[sector-index] 전체 수집 완료 — attempted={}, succeeded={}, skipped={}",
                result.attempted(),
                result.succeeded(),
                result.skipped());
        return result;
    }

    private int collectSymbol(Stock stock, String symbol, LeaseSession session, LocalDate today)
            throws InterruptedException {
        LocalDate fromDate = today.minusDays(LOOKBACK_CALENDAR_DAYS);
        String from = fromDate.format(DATE_FMT);
        String to = today.format(DATE_FMT);

        // REQ-BATCH3-020: U 전용 API (J 주식 API 사용 금지 — REQ-BATCH3-021b). 게이트 경유(REQ-KISGATE-001).
        KisSectorIndexResponse response =
                guardedKisExecutor.execute(
                        session,
                        uri ->
                                uri.path(PATH)
                                        .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                                        .queryParam("FID_INPUT_ISCD", symbol)
                                        .queryParam("FID_INPUT_DATE_1", from)
                                        .queryParam("FID_INPUT_DATE_2", to)
                                        .queryParam("FID_PERIOD_DIV_CODE", "D")
                                        .build(),
                        TR_ID,
                        KisSectorIndexResponse.class);

        // REQ-BATCH3-073: 빈 output2 → 0건 성공 (휴장일·신규 없음 등)
        if (response.output2().isEmpty()) {
            return 0;
        }

        // REQ-INSERT-006: 유효 행 누적 후 단일 배치 INSERT IGNORE (W-2 불변식)
        List<ParsedOhlcvRow> validRows = new ArrayList<>();
        for (KisSectorIndexResponse.SectorIndexRow row : response.output2()) {
            ParsedOhlcvRow parsed = saveIfValid(symbol, row);
            if (parsed != null) {
                validRows.add(parsed);
            }
        }

        if (!validRows.isEmpty()) {
            ohlcvInserter.insertBatch(stock.getId(), validRows);
        }
        return validRows.size();
    }

    /**
     * 행 검증 후 파싱된 결과를 반환한다 (REQ-INSERT-006, W-1 불변식).
     *
     * @return 검증 통과 시 {@link ParsedOhlcvRow}, 실패 시 empty
     */
    private ParsedOhlcvRow saveIfValid(String symbol, KisSectorIndexResponse.SectorIndexRow row) {
        // REQ-BATCH3-070: null 키 필드 skip
        if (row.stckBsopDate() == null
                || row.stckBsopDate().isBlank()
                || row.bstpNmixPrpr() == null
                || row.bstpNmixPrpr().isBlank()) {
            log.warn(
                    "[sector-index] 검증 실패 (null 키 필드) — symbol={}, date={}",
                    symbol,
                    row.stckBsopDate());
            return null;
        }
        try {
            BigDecimal close = new BigDecimal(row.bstpNmixPrpr());
            // REQ-BATCH3-070: 종가 ≤ 0 skip
            if (close.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn(
                        "[sector-index] 검증 실패 (종가 ≤ 0) — symbol={}, date={}, close={}",
                        symbol,
                        row.stckBsopDate(),
                        row.bstpNmixPrpr());
                return null;
            }

            LocalDate tradeDate = LocalDate.parse(row.stckBsopDate(), DATE_FMT);
            BigDecimal open = parsePriceOrZero(row.bstpNmixOprc());
            BigDecimal high = parsePriceOrZero(row.bstpNmixHgpr());
            BigDecimal low = parsePriceOrZero(row.bstpNmixLwpr());
            long volume = parseLongOrZero(row.acmlVol());
            long tradingValue = parseLongOrZero(row.acmlTrPbmn());

            return new ParsedOhlcvRow(tradeDate, open, high, low, close, volume, tradingValue);
        } catch (NumberFormatException e) {
            log.warn(
                    "[sector-index] 파싱 실패 — symbol={}, date={}, error={}",
                    symbol,
                    row.stckBsopDate(),
                    e.getMessage());
            return null;
        }
    }

    private static BigDecimal parsePriceOrZero(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static long parseLongOrZero(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
