package com.aaa.collector.stock.daily;

import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 미국(해외) 일봉 OHLCV 수집 서비스 (SPEC-COLLECTOR-OVERSEAS-OHLCV-001).
 *
 * <p>활성 미국 시장(NYSE/NASDAQ/AMEX) STOCK·ETF 종목을 대상으로 KIS {@code dailyprice}(TR {@code HHDFS76240000},
 * 명세22) API에서 일봉을 수집하여 {@code daily_ohlcv}에 원주가({@code MODP=0})로 멱등 저장한다. 국내 일봉 파이프라인(BATCH-001)
 * 구조를 답습하되 아래 해외 고유 규칙을 적용한다.
 *
 * <ul>
 *   <li><b>MODP=0 = 원주가</b>: 국내 {@code FID_ORG_ADJ_PRC=1}=원주가와 의미가 반대다. 국내 {@code 1}을 복사하면 수정주가가
 *       적재되어 store-raw(OHLCV-002)를 위반한다(REQ-OVOH-003a).
 *   <li><b>ET 당일 행 가드</b>: cron 16:30 ET 발화 = 서버 KST 익일 새벽이므로 {@code LocalDate.now(ET)}와 {@code
 *       xymd}를 비교한다. KST 비교는 가드를 무력화한다(REQ-OVOH-015).
 *   <li><b>tvol/tamt 상한 없음</b>: 양수(>0)만 검증한다 — 미국 대형주·ETF의 정상 거래대금이 국내 스케일을 정당하게 초과하므로(AAPL
 *       tamt=1.27e10) 국내 {@code VOLUME_MAX}를 재사용하지 않는다(REQ-OVOH-012, MA-01).
 *   <li><b>mod_yn 부재</b>: 명세22에 변경여부 필드가 없어 국내 수정주가행 제외 단계가 자연 누락된다(MI-03).
 * </ul>
 *
 * <p>키 분산·graceful skip·모든 키 죽음 처리는 국내와 동일하게 {@link HealthyKeyRoundRobinDistributor}/{@link
 * BatchRestExecutor}를 재사용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 미국 일봉 수집 진입점 — 건강 키 분산·EXCD 라우팅·zdiv/빈응답/ET 당일 가드·검증·멱등 저장·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-OVERSEAS-OHLCV-001 REQ-OVOH-001~005,012,013,015,016,017,030,031 —
// 멀티키 진입점으로 다수 협력자(repository·distributor·executor)가 수렴하는 fan-in 경계
// @MX:SPEC: SPEC-COLLECTOR-OVERSEAS-OHLCV-001
public class OverseasDailyOhlcvCollectionService {

    /** KIS dailyprice TR ID (명세22, 개별주식·ETF 공통). */
    private static final String DAILY_PRICE_TR_ID = "HHDFS76240000";

    /** 미국 시장 거래일 판정 zone — cron 16:30 ET 발화가 서버 KST 익일이므로 ET 기준 당일 행 가드 필수(REQ-OVOH-015). */
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 가격 극단값 상한 (USD 100,000 — 버크셔 A주류 초고가 예외 제외 정규 주식·ETF 포괄 보수 상한).
     *
     * <p>국내 {@code PRICE_MAX}(₩1억)를 재사용하지 않는다 — USD 스케일로 재산정(REQ-OVOH-012).
     */
    private static final BigDecimal PRICE_MAX_USD = new BigDecimal("100000");

    /**
     * {@code output1.zdiv} 허용 상한 — {@code daily_ohlcv} 가격 {@code DECIMAL(18,4)}
     * scale(REQ-OVOH-016).
     */
    private static final int ZDIV_MAX = 4;

    private final StockRepository stockRepository;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final BatchRestExecutor batchRestExecutor;
    private final HealthyKeyRoundRobinDistributor distributor;

    /**
     * 미국 일봉 수집을 실행하고 집계 결과를 반환한다.
     *
     * @param today 수집 기준 ET 거래일 ({@code BYMD} 및 당일 행 가드에 사용)
     * @return 시도/성공/skip 종목 수 집계
     */
    public CollectionResult collect(LocalDate today) {
        // REQ-OVOH-001: 활성 미국(NYSE/NASDAQ/AMEX) STOCK+ETF만 — 국내·INDEX 제외(StockRepository 계층 캡슐화)
        List<Stock> activeStocks = stockRepository.findAllActiveOverseasTradable();

        if (activeStocks.isEmpty()) {
            log.info("[overseas-daily] 수집 대상 없음 — activeStocks=0");
            return new CollectionResult(0, 0, 0);
        }

        // REQ-OVOH-002: stock→key 할당을 distributor에 위임(healthy-key round-robin)
        Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(activeStocks);

        int total = activeStocks.size();

        // REQ-OVOH-031: empty allocation = 모든 키 죽음 → skip-all + ERROR + 폴백 없음
        if (allocation.isEmpty()) {
            log.error(
                    "[overseas-daily] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
                    total,
                    total);
            return new CollectionResult(total, 0, total);
        }

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        // Virtual Thread executor — 종목별 블로킹(limiter.consume)을 commonPool 점유 없이 처리.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            allocation.forEach(
                    (credential, stocks) -> {
                        for (Stock stock : stocks) {
                            executor.submit(
                                    () ->
                                            collectStock(
                                                    stock, credential, today, succeeded, skipped));
                        }
                    });
        } // close() blocks until all submitted tasks complete

        return new CollectionResult(total, succeeded.get(), skipped.get());
    }

    private void collectStock(
            Stock stock,
            KisAccountCredential credential,
            LocalDate today,
            AtomicInteger succeeded,
            AtomicInteger skipped) {

        String symbol = stock.getSymbol();
        String excd;
        try {
            // REQ-OVOH-002: 미국 시장만 EXCD 매핑 — 미매핑 시 skip+WARN(방어적)
            excd = stock.getMarket().toDailyPriceExcd();
        } catch (IllegalStateException e) {
            log.warn(
                    "[overseas-daily] EXCD 매핑 불가로 skip — symbol={}, market={}",
                    symbol,
                    stock.getMarket());
            skipped.incrementAndGet();
            return;
        }

        try {
            BatchResult<KisOverseasDailyOhlcvResponse> batchResult =
                    fetchBatch(credential, excd, symbol, today);

            if (!batchResult.isSuccess()) {
                String reason = batchResult.getSkipReason().orElse("알 수 없음");
                log.warn("[overseas-daily] skip (데이터 유실) — symbol={}, reason={}", symbol, reason);
                skipped.incrementAndGet();
                return;
            }

            KisOverseasDailyOhlcvResponse response = batchResult.getValue().orElseThrow();

            if (!saveValidRows(stock, symbol, response, today)) {
                skipped.incrementAndGet();
                return;
            }
            succeeded.incrementAndGet();

        } catch (KisTokenIssueException e) {
            // REQ-OVOH-013: token 발급 실패 → graceful skip
            log.warn(
                    "[overseas-daily] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        }
    }

    private BatchResult<KisOverseasDailyOhlcvResponse> fetchBatch(
            KisAccountCredential credential, String excd, String symbol, LocalDate today) {
        String bymd = today.format(DATE_FMT);
        return batchRestExecutor.execute(
                credential,
                uri ->
                        uri.path("/uapi/overseas-price/v1/quotations/dailyprice")
                                .queryParam("AUTH", "")
                                .queryParam("EXCD", excd)
                                .queryParam("SYMB", symbol)
                                .queryParam("GUBN", "0")
                                // REQ-OVOH-003a: MODP=0 = 원주가. 국내 FID_ORG_ADJ_PRC=1과 의미 반대 — 1 복사
                                // 금지.
                                .queryParam("MODP", "0")
                                .queryParam("BYMD", bymd)
                                .build(),
                DAILY_PRICE_TR_ID,
                KisOverseasDailyOhlcvResponse.class,
                symbol);
    }

    /**
     * 유효 행을 멱등 저장한다.
     *
     * @return 종목이 적재 처리되었으면(빈응답/zdiv 가드에 걸리지 않았으면) {@code true}, 종목 단위 skip이면 {@code false}
     */
    private boolean saveValidRows(
            Stock stock, String symbol, KisOverseasDailyOhlcvResponse response, LocalDate today) {
        // REQ-OVOH-017: rt_cd=0이어도 output 비면 종목 skip(잘못된 EXCD·심볼·미존재 종목)
        if (response.output1() == null || response.output2().isEmpty()) {
            log.warn("[overseas-daily] 빈 응답으로 skip — symbol={}", symbol);
            return false;
        }

        // REQ-OVOH-016: zdiv>4 → 종목 skip+WARN(DECIMAL(18,4) scale 초과 silent 손실 방지)
        int zdiv = parseZdiv(response.output1().zdiv());
        if (zdiv > ZDIV_MAX) {
            log.warn("[overseas-daily] zdiv>4 종목 skip — symbol={}, zdiv={}", symbol, zdiv);
            return false;
        }

        for (KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row : response.output2()) {
            // REQ-OVOH-015: ET 거래일 기준 당일 행 제외(미마감/장중 스냅샷). KST 비교 금지.
            if (isEtToday(row.xymd(), today)) {
                continue;
            }
            if (!isValid(symbol, row)) {
                continue;
            }
            insertRow(stock, row);
        }
        return true;
    }

    private int parseZdiv(String zdiv) {
        try {
            return Integer.parseInt(zdiv);
        } catch (NumberFormatException e) {
            // zdiv 파싱 실패는 정상 미발생(실측 전부 "4") — 보수적으로 상한 초과로 취급해 skip 유도
            return ZDIV_MAX + 1;
        }
    }

    /** {@code xymd}가 ET 거래일 기준 당일과 같은지 판정한다(REQ-OVOH-015 — ET zone 고정). */
    private boolean isEtToday(String xymd, LocalDate today) {
        return today.format(DATE_FMT).equals(xymd);
    }

    private void insertRow(Stock stock, KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row) {
        BigDecimal open = new BigDecimal(row.open());
        BigDecimal high = new BigDecimal(row.high());
        BigDecimal low = new BigDecimal(row.low());
        BigDecimal close = new BigDecimal(row.clos());
        long volume = Long.parseLong(row.tvol());
        long tradingValue = Long.parseLong(row.tamt());

        dailyOhlcvRepository.insertIgnoreDuplicate(
                stock.getId(),
                LocalDate.parse(row.xymd(), DATE_FMT),
                open,
                high,
                low,
                close,
                volume,
                tradingValue);
    }

    /**
     * 일봉 행 검증 규칙 (REQ-OVOH-012, MA-01).
     *
     * <ul>
     *   <li>가격(clos/open/high/low) ≤ 0 또는 > USD 100,000 → invalid(상·하한 양방).
     *   <li>거래량(tvol)·거래대금(tamt) ≤ 0 → invalid. <b>상한 가드 없음</b> — 미국 대형주·ETF의 정상 거래대금이 국내 스케일을 정당하게
     *       초과한다(AAPL tamt=1.27e10).
     *   <li>null / 숫자 파싱 실패 → invalid.
     * </ul>
     */
    private boolean isValid(
            String symbol, KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row) {
        try {
            BigDecimal close = new BigDecimal(row.clos());
            BigDecimal open = new BigDecimal(row.open());
            BigDecimal high = new BigDecimal(row.high());
            BigDecimal low = new BigDecimal(row.low());
            long volume = Long.parseLong(row.tvol());
            long tradingValue = Long.parseLong(row.tamt());

            boolean invalid =
                    isInvalidPrice(close)
                            || isInvalidPrice(open)
                            || isInvalidPrice(high)
                            || isInvalidPrice(low)
                            // tvol/tamt: 양수만 검증 — 상한 없음(MA-01)
                            || volume <= 0
                            || tradingValue <= 0;

            if (invalid) {
                log.warn(
                        "[overseas-daily] 검증 실패 (데이터 유실) — symbol={}, date={}, clos={}, open={}, high={}, low={}, tvol={}, tamt={}",
                        symbol,
                        row.xymd(),
                        row.clos(),
                        row.open(),
                        row.high(),
                        row.low(),
                        row.tvol(),
                        row.tamt());
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            log.warn(
                    "[overseas-daily] 숫자 파싱 실패 (데이터 유실) — symbol={}, date={}, clos={}, open={}, high={}, low={}, tvol={}, tamt={}",
                    symbol,
                    row.xymd(),
                    row.clos(),
                    row.open(),
                    row.high(),
                    row.low(),
                    row.tvol(),
                    row.tamt());
            return false;
        }
    }

    /** 가격 검증: ≤ 0 또는 > USD 100,000이면 invalid(상·하한 양방, REQ-OVOH-012). */
    private boolean isInvalidPrice(BigDecimal price) {
        return price.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(PRICE_MAX_USD) > 0;
    }
}
