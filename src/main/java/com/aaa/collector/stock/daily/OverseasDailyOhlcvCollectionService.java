package com.aaa.collector.stock.daily;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

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
 * <p>키 선택: {@link GuardedKisExecutor} 단일 게이트를 경유한다. 배치 시작 시 {@link
 * KeyLeaseRegistry#openSession()}으로 per-batch 헬스 스냅샷을 1회 고정(REQ-KISGATE-006a)하고, 종목별 게이트 호출이 그 세션에서
 * least-busy 키를 동적 lease한다 (SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001,-020). 모든 키 죽음·graceful skip
 * 종단 동작은 국내(BATCH-001)와 동일하게 보존한다(REQ-KISGATE-024, REQ-KISGATE-022).
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 미국 일봉 수집 진입점 — 게이트 경유 키 lease·EXCD 라우팅·zdiv/빈응답/ET 당일 가드·검증·멱등 저장·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-OVERSEAS-OHLCV-001 REQ-OVOH-001~005,012,013,015,016,017,030,031,
// SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001,-020,-024 — 게이트 경유 멀티키 진입점으로 다수 협력자가 수렴하는 fan-in 경계
// @MX:SPEC: SPEC-COLLECTOR-OVERSEAS-OHLCV-001, SPEC-COLLECTOR-KISGATE-001
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
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

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

        // REQ-KISGATE-006a: per-batch 헬스 스냅샷 1회 고정 (selectHealthy 배치당 1회 호출)
        LeaseSession session = keyLeaseRegistry.openSession();

        int total = activeStocks.size();

        // REQ-OVOH-031, REQ-KISGATE-024 보존: 빈 스냅샷 = 전 키 사망 → per-stock 수집 0회, 전체 skip, ERROR + no
        // fallback
        if (session.isEmpty()) {
            log.error(
                    "[overseas-daily] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
                    total,
                    total);
            return new CollectionResult(total, 0, total);
        }

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        // Virtual Thread executor — 종목별 블로킹(limiter.consume)을 commonPool 점유 없이 처리.
        // 키 선택은 게이트가 세션 스냅샷에서 동적 lease한다(REQ-KISGATE-020). 모든 종목이 동일 세션을 공유한다(REQ-KISGATE-031).
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Stock stock : activeStocks) {
                executor.submit(() -> collectStock(stock, session, today, succeeded, skipped));
            }
        } // close() blocks until all submitted tasks complete

        return new CollectionResult(total, succeeded.get(), skipped.get());
    }

    private void collectStock(
            Stock stock,
            LeaseSession session,
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
            KisOverseasDailyOhlcvResponse response = fetch(session, excd, symbol, today);

            if (!saveValidRows(stock, symbol, response, today)) {
                skipped.incrementAndGet();
                return;
            }
            succeeded.incrementAndGet();

        } catch (KisRateLimitException | RestClientException e) {
            // REQ-KISGATE-022: retryable 재시도 소진 → graceful skip (기존 BatchResult.skip 등가 종단 동작)
            log.warn(
                    "[overseas-daily] skip (재시도 소진) — symbol={}, reason={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        } catch (InterruptedException e) {
            // RETRY-001 REQ-RETRY-017 보존: 인터럽트 플래그 복원 후 skip (전파 아님)
            Thread.currentThread().interrupt();
            log.warn("[overseas-daily] 인터럽트 — symbol={} skip", symbol);
            skipped.incrementAndGet();
        } catch (NoHealthyKeyException e) {
            // 방어적: collect()에서 단락되므로 정상 운용에서는 도달하지 않음
            log.warn("[overseas-daily] 건강 키 0개로 skip — symbol={}", symbol);
            skipped.incrementAndGet();
        } catch (KisTokenIssueException e) {
            // REQ-OVOH-013: token 발급 실패 → graceful skip
            log.warn(
                    "[overseas-daily] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        }
    }

    /**
     * 백필용 윈도우 1구간 수집 — 당일 수집과 동일한 EXCD 라우팅·검증·INSERT IGNORE 경로를 재사용한다 (REQ-BACKFILL-002).
     *
     * <p>당일 경로({@link #collect(LocalDate)})와 차이는 두 가지뿐이다: (1) {@code anchor}({@code BYMD})를 호출자가 직접
     * 지정해 과거 100영업일 윈도우를 받을 수 있고(REQ-BACKFILL-013a — 해외는 anchor 전진만으로 충분), (2) 종료 판정 입력인 {@link
     * BackfillWindowResult}를 반환한다. ET 당일 행 가드({@link #isEtToday})는 anchor 기준 그대로 적용되며, 백필 anchor는
     * 과거일이라 반환 행이 anchor보다 과거이므로 가드가 자연히 발동하지 않는다.
     *
     * <p>EXCD 미매핑·예외는 호출자(T6)가 상태 전이로 처리하도록 전파한다.
     *
     * @param stock 백필 대상 미국 종목 (활성, REQ-BACKFILL-006)
     * @param session 호출자가 1회 고정한 per-run 헬스 스냅샷 세션
     * @param anchor 윈도우 기준일 ({@code BYMD})
     * @return 적재 대상 행의 최소 거래일 + 행 수 (적재 대상 없으면 {@link BackfillWindowResult#EMPTY})
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    public BackfillWindowResult collectWindow(Stock stock, LeaseSession session, LocalDate anchor)
            throws InterruptedException {
        String symbol = stock.getSymbol();
        String excd = stock.getMarket().toDailyPriceExcd();
        KisOverseasDailyOhlcvResponse response = fetch(session, excd, symbol, anchor);

        if (response.output1() == null || response.output2().isEmpty()) {
            return BackfillWindowResult.EMPTY;
        }
        if (parseZdiv(response.output1().zdiv()) > ZDIV_MAX) {
            return BackfillWindowResult.EMPTY;
        }

        List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> kept =
                keepAndInsertRows(stock, symbol, response, anchor);
        if (kept.isEmpty()) {
            return BackfillWindowResult.EMPTY;
        }
        // 이미 검증·적재된 행에서 최소 거래일을 도출한다(2차 재파싱 없음) — xymd(yyyyMMdd)는 사전식=연대순.
        String oldest =
                kept.stream()
                        .map(KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow::xymd)
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        return new BackfillWindowResult(LocalDate.parse(oldest, DATE_FMT), kept.size());
    }

    private KisOverseasDailyOhlcvResponse fetch(
            LeaseSession session, String excd, String symbol, LocalDate today)
            throws InterruptedException {
        String bymd = today.format(DATE_FMT);
        Function<UriBuilder, URI> uriCustomizer =
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
                                .build();
        return guardedKisExecutor.execute(
                session, uriCustomizer, DAILY_PRICE_TR_ID, KisOverseasDailyOhlcvResponse.class);
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

        keepAndInsertRows(stock, symbol, response, today);
        return true;
    }

    /**
     * [T3] fetch 경로 전용 — ET 당일 행 제외·검증만 수행하고 INSERT하지 않는다.
     *
     * <p>{@link #fetchWindow}가 사용하는 순수 검증 경로. {@link #keepAndInsertRows}는 이 결과에 적재를
     * 추가한다(REQ-BACKFILL-002). 호출자는 빈응답·zdiv 가드를 사전에 통과시킨 뒤 호출한다.
     *
     * @return 검증 통과 행 목록 (없으면 빈 목록)
     */
    private List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> keepValidRows(
            String symbol, KisOverseasDailyOhlcvResponse response, LocalDate today) {
        List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> kept = new ArrayList<>();
        for (KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row : response.output2()) {
            if (isEtToday(row.xymd(), today)) {
                continue;
            }
            if (!isValid(symbol, row)) {
                continue;
            }
            kept.add(row);
        }
        return kept;
    }

    /**
     * ET 당일 행 제외·검증 통과 행을 멱등 적재하고, 적재한 행 목록을 반환한다(REQ-OVOH-015, -012).
     *
     * <p>당일 경로({@link #saveValidRows})는 반환값을 무시하고, 백필 경로({@link #collectWindow})는 최소 거래일·행 수 도출에
     * 사용한다 — 동일 ET 가드·검증·적재 경로를 공유한다(REQ-BACKFILL-002). 호출자는 빈응답·zdiv 가드를 사전에 통과시킨 뒤 호출한다.
     *
     * @return 적재한 행 목록 (없으면 빈 목록)
     */
    private List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> keepAndInsertRows(
            Stock stock, String symbol, KisOverseasDailyOhlcvResponse response, LocalDate today) {
        List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> kept =
                keepValidRows(symbol, response, today);
        for (KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row : kept) {
            insertRow(stock, row);
        }
        return kept;
    }

    /**
     * [T3] fetch 단계 — HTTP 호출·빈응답/zdiv 가드·ET 당일 제외·검증을 수행하고 INSERT하지 않는다.
     *
     * <p>{@code anchor}(= BYMD)를 호출자가 직접 전달한다. 검증 통과 행과 최소 거래일을 {@link OverseasDailyOhlcvFetch}로
     * 반환한다. DB 접촉 없음.
     *
     * @param anchor 윈도우 기준일 (BYMD, BackfillWindowExecutor가 resolveStatusForFetch로 계산해서 전달)
     * @param stock 백필 대상 미국 종목
     * @param session 호출자가 고정한 per-run 헬스 스냅샷 세션
     * @return 검증 통과 행 목록 + 최소 거래일 + 행 수 (없으면 rows=빈목록, oldestTradeDate=null, rowCount=0)
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    // @MX:NOTE: [AUTO] fetchWindow — 비tx HTTP 단계. DB 미접촉. BackfillWindowExecutor가 @Transactional
    // persistWindow와 교차 빈으로 순차 호출.
    public OverseasDailyOhlcvFetch fetchWindow(LocalDate anchor, Stock stock, LeaseSession session)
            throws InterruptedException {
        String symbol = stock.getSymbol();
        String excd = stock.getMarket().toDailyPriceExcd();
        KisOverseasDailyOhlcvResponse response = fetch(session, excd, symbol, anchor);

        if (response.output1() == null || response.output2().isEmpty()) {
            return new OverseasDailyOhlcvFetch(List.of(), null, 0);
        }
        if (parseZdiv(response.output1().zdiv()) > ZDIV_MAX) {
            return new OverseasDailyOhlcvFetch(List.of(), null, 0);
        }

        List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> kept =
                keepValidRows(symbol, response, anchor);
        if (kept.isEmpty()) {
            return new OverseasDailyOhlcvFetch(List.of(), null, 0);
        }
        String oldest =
                kept.stream()
                        .map(KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow::xymd)
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        return new OverseasDailyOhlcvFetch(kept, LocalDate.parse(oldest, DATE_FMT), kept.size());
    }

    /**
     * [T3] persist 단계 — {@link OverseasDailyOhlcvFetch}의 행을 INSERT IGNORE로 적재한다.
     *
     * <p>@Transactional 없음 — 트랜잭션은 {@code BackfillWindowExecutor}(T7)가 소유한다(REQ-TXBOUNDARY-002).
     *
     * @param stock 백필 대상 종목
     * @param fetch fetchWindow가 반환한 DTO
     * @return 적재 대상 행의 최소 거래일 + 행 수 (fetch가 빈 경우 {@link BackfillWindowResult#EMPTY})
     */
    // @MX:NOTE: [AUTO] persistWindow — BackfillWindowExecutor @Transactional에 MANDATORY 전파로 합류.
    // INSERT + 결과 구성 담당.
    @Transactional(propagation = Propagation.MANDATORY)
    public BackfillWindowResult persistWindow(Stock stock, OverseasDailyOhlcvFetch fetch) {
        if (fetch.rows().isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[overseas-daily][backfill] persistWindow 스킵 (빈 fetch) — symbol={}",
                        stock.getSymbol());
            }
            return BackfillWindowResult.EMPTY;
        }
        for (KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row : fetch.rows()) {
            insertRow(stock, row);
        }
        return new BackfillWindowResult(fetch.oldestTradeDate(), fetch.rowCount());
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
