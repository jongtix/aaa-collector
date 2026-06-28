package com.aaa.collector.stock.daily;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

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
 *
 * <p>REQ-INSERT-005: {@link #keepValidRows}에서 행별 파싱을 1회만 수행하여 {@link ParsedOhlcvRow}로 수집하고, {@link
 * WarningCountingOhlcvInserter#insertBatch(Long, List)}로 단일 커넥션 배치 INSERT IGNORE 수행 (W-2 불변식).
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
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final WarningCountingOhlcvInserter ohlcvInserter;
    private final UsMarketOpenGate usMarketOpenGate;

    /**
     * 미국 일봉 수집을 실행하고 집계 결과를 반환한다.
     *
     * @param today 수집 기준 ET 거래일 ({@code BYMD} 및 당일 행 가드에 사용)
     * @return 시도/성공/skip 종목 수 집계
     */
    public CollectionResult collect(LocalDate today) {
        // REQ-USMKT-012: 미장 휴장일 → skip (UsMarketSessionGate 게이트)
        if (!usMarketOpenGate.isOpenDay(today)) {
            log.info("[overseas-daily] {} 미장 휴장일 → skip", today);
            return new CollectionResult(0, 0, 0);
        }

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
     * @param stock 백필 대상 미국 종목 (활성, REQ-BACKFILL-006)
     * @param session 호출자가 1회 고정한 per-run 헬스 스냅샷 세션
     * @param anchor 윈도우 기준일 ({@code BYMD})
     * @return 적재 대상 행의 최소 거래일 + 행 수 (적재 대상 없으면 {@link BackfillWindowResult#EMPTY})
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    // @MX:WARN: [AUTO] legacy collectWindow — 반환하는 BackfillWindowResult.rawRowCount가 kept.size()(거부
    // 후 행수)와 동일.
    // @MX:REASON: 이 메서드를 GROUP_A 종료 판정(decideGroupA)에 연결하면 SPEC-COLLECTOR-BACKFILL-006 조기종료 버그를
    // 재현한다.
    // @MX:REASON: 프로덕션 백필은 fetchWindow→persistWindow 경로를 사용하므로 현재 미사용. 삭제 금지(인터페이스 일관성).
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

        List<ParsedOhlcvRow> kept = keepAndInsertRows(stock, symbol, response, anchor);
        if (kept.isEmpty()) {
            return BackfillWindowResult.EMPTY;
        }
        LocalDate oldest =
                kept.stream()
                        .map(ParsedOhlcvRow::tradeDate)
                        .min(LocalDate::compareTo)
                        .orElseThrow();
        return new BackfillWindowResult(oldest, kept.size());
    }

    private KisOverseasDailyOhlcvResponse fetch(
            LeaseSession session, String excd, String symbol, LocalDate today)
            throws InterruptedException {
        String bymd = today.format(DATE_FMT);
        return guardedKisExecutor.execute(
                session,
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
                KisOverseasDailyOhlcvResponse.class);
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
     * ET 당일 행 제외·검증 통과 행 목록을 반환한다 (REQ-INSERT-005, W-1 불변식).
     *
     * <p>파싱을 1회만 수행하여 {@link ParsedOhlcvRow}로 수집한다 — 이미 파싱된 값을 후속 단계가 재사용한다.
     *
     * @return 검증 통과 ParsedOhlcvRow 목록 (없으면 빈 목록)
     */
    private List<ParsedOhlcvRow> keepValidRows(
            String symbol, KisOverseasDailyOhlcvResponse response, LocalDate today) {
        List<ParsedOhlcvRow> kept = new ArrayList<>();
        for (KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row : response.output2()) {
            if (isEtToday(row.xymd(), today)) {
                continue;
            }
            ParsedOhlcvRow parsed = parseIfValid(symbol, row);
            if (parsed != null) {
                kept.add(parsed);
            }
        }
        return kept;
    }

    /**
     * ET 당일 행 제외·검증 통과 행을 파싱 후 단일 커넥션 배치 INSERT IGNORE로 멱등 적재하고 행 목록을 반환한다 (REQ-INSERT-005, W-2
     * 불변식).
     *
     * <p>당일 경로는 반환값을 무시하고, 백필 경로는 최소 거래일·행 수 도출에 사용한다(REQ-BACKFILL-002).
     *
     * @return 적재한 ParsedOhlcvRow 목록 (없으면 빈 목록)
     */
    private List<ParsedOhlcvRow> keepAndInsertRows(
            Stock stock, String symbol, KisOverseasDailyOhlcvResponse response, LocalDate today) {
        List<ParsedOhlcvRow> kept = keepValidRows(symbol, response, today);
        if (!kept.isEmpty()) {
            // REQ-INSERT-005: 단일 커넥션 배치 INSERT IGNORE — 커넥션 1회 (W-2 불변식)
            ohlcvInserter.insertBatch(stock.getId(), kept);
        }
        return kept;
    }

    /**
     * [T3] fetch 단계 — HTTP 호출·빈응답/zdiv 가드·ET 당일 제외·검증을 수행하고 INSERT하지 않는다.
     *
     * @param anchor 윈도우 기준일 (BYMD)
     * @param stock 백필 대상 미국 종목
     * @param session 호출자가 고정한 per-run 헬스 스냅샷 세션
     * @return 검증 통과 행 목록 + 최소 거래일 + 행 수 (없으면 rows=빈목록, oldestTradeDate=null, rowCount=0)
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    // @MX:NOTE: [AUTO] fetchWindow — 비tx HTTP 단계. DB 미접촉. BackfillWindowExecutor가 @Transactional
    // persistWindow와 교차 빈으로 순차 호출.
    // @MX:NOTE: [AUTO] rawRowCount = ET 당일 가드 제외 후 원본 행수(거부 전, mod_yn 부재) — GROUP_A 종료 판정 전용.
    // @MX:REASON: SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-091,-088
    public OverseasDailyOhlcvFetch fetchWindow(LocalDate anchor, Stock stock, LeaseSession session)
            throws InterruptedException {
        String symbol = stock.getSymbol();
        String excd = stock.getMarket().toDailyPriceExcd();
        KisOverseasDailyOhlcvResponse response = fetch(session, excd, symbol, anchor);

        if (response.output1() == null || response.output2().isEmpty()) {
            return new OverseasDailyOhlcvFetch(List.of(), null, 0, 0);
        }
        if (parseZdiv(response.output1().zdiv()) > ZDIV_MAX) {
            return new OverseasDailyOhlcvFetch(List.of(), null, 0, 0);
        }

        int rawRowCount = rawRowCount(response, anchor);
        List<ParsedOhlcvRow> kept = keepValidRows(symbol, response, anchor);
        if (kept.isEmpty()) {
            return new OverseasDailyOhlcvFetch(List.of(), null, 0, rawRowCount);
        }
        LocalDate oldest =
                kept.stream()
                        .map(ParsedOhlcvRow::tradeDate)
                        .min(LocalDate::compareTo)
                        .orElseThrow();
        return new OverseasDailyOhlcvFetch(kept, oldest, kept.size(), rawRowCount);
    }

    /**
     * KIS 원본 응답 행수를 산정한다 — ET 당일 가드({@link #isEtToday}) 제외 후·검증(volume/OHLC) 거부 전 행수(DP-1 해외).
     *
     * <p>해외는 {@code mod_yn}(수정주가 플래그)이 명세에 없어(MI-03) 국내의 modYn 제외 단계가 없다. ET 당일 가드 제외는 별개 정당 필터이므로
     * 종료 판정 입력에서도 제외한다(REQ-OVOH-015 보존). 검증 거부는 카운트에서 제외하지 않는다 — 비분할 거래정지 거부가 종료를 흔들지 않게 하는 것이 본
     * 산정의 목적이다(SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-091).
     */
    private int rawRowCount(KisOverseasDailyOhlcvResponse response, LocalDate today) {
        return (int)
                response.output2().stream().filter(row -> !isEtToday(row.xymd(), today)).count();
    }

    /**
     * [T3] persist 단계 — {@link OverseasDailyOhlcvFetch}의 행을 INSERT IGNORE로 적재한다.
     *
     * <p>REQ-INSERT-005: ParsedOhlcvRow를 직접 바인딩 — 추가 파싱 없음 (W-1 불변식).
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
        ohlcvInserter.insertBatch(stock.getId(), fetch.rows());
        // SPEC-COLLECTOR-BACKFILL-006: rawRowCount(원본 행수)를 GROUP_A 종료 입력으로 전달. rowCount(저장 행수) 보존.
        return new BackfillWindowResult(
                fetch.oldestTradeDate(), fetch.rowCount(), fetch.rawRowCount());
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

    /**
     * 일봉 행 검증 규칙 (REQ-OVOH-012, MA-01, REQ-INSERT-005).
     *
     * <p>파싱 성공 시 파싱된 값을 담은 {@link ParsedOhlcvRow}를 반환 — 1회 파싱, 재사용 (W-1 불변식).
     *
     * <ul>
     *   <li>가격(close/open/high/low) ≤ 0 또는 {@code > PRICE_MAX_USD} → empty
     *   <li>거래량 &lt; 0(음수) → empty; 거래정지일(OHLC 유효 + {@code volume == 0})은 허용(저장)
     *   <li>정상 행({@code volume > 0})의 {@code tvol}/{@code tamt} ≤ 0 → empty(MA-01); 거래정지 행은 {@code
     *       tamt == 0} 허용(DP-4)
     * </ul>
     *
     * <p>SPEC-COLLECTOR-BACKFILL-006: 미국 분할은 거래정지 {@code volume == 0}이 실측상 없으나(§1.5), 비분할 거래정지
     * 사유(규제·변동성·정리매매)에 대한 방어로 국내와 동일하게 거래정지일을 보존 저장한다.
     */
    private ParsedOhlcvRow parseIfValid(
            String symbol, KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row) {
        try {
            BigDecimal close = new BigDecimal(row.clos());
            BigDecimal open = new BigDecimal(row.open());
            BigDecimal high = new BigDecimal(row.high());
            BigDecimal low = new BigDecimal(row.low());
            long volume = Long.parseLong(row.tvol());
            long tradingValue = Long.parseLong(row.tamt());

            boolean invalidPrice =
                    isInvalidPrice(close)
                            || isInvalidPrice(open)
                            || isInvalidPrice(high)
                            || isInvalidPrice(low);
            // @MX:NOTE: [AUTO] 거래정지일(OHLC 유효 + volume==0) 허용 — 미국 분할은 거래정지 volume=0 미발생(실측),
            // 비분할 사유(규제·변동성·정리매매) 방어. 거래정지 행은 tamt==0도 허용, 정상 행은 tvol/tamt 양수 검증 유지(MA-01).
            // @MX:REASON: SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-090,-093 — 국내와 일관 + 종료 오판 사전 방어.
            boolean tradingHalt = !invalidPrice && volume == 0;
            boolean invalid =
                    invalidPrice
                            || volume < 0
                            // 거래정지 행(volume==0)은 tamt==0 허용(DP-4); 정상 행(volume>0)은 tvol/tamt 양수
                            // 검증(MA-01)
                            || (!tradingHalt && tradingValue <= 0);

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
                return null;
            }
            LocalDate tradeDate = LocalDate.parse(row.xymd(), DATE_FMT);
            return new ParsedOhlcvRow(tradeDate, open, high, low, close, volume, tradingValue);
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
            return null;
        }
    }

    /** 가격 검증: ≤ 0 또는 > USD 100,000이면 invalid(상·하한 양방, REQ-OVOH-012). */
    private boolean isInvalidPrice(BigDecimal price) {
        return price.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(PRICE_MAX_USD) > 0;
    }
}
