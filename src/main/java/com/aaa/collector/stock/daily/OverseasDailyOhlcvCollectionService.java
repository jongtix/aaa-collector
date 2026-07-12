package com.aaa.collector.stock.daily;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.observability.WatermarkSeries;
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
// PMD.GodClass: 당일 배치·백필 fetch/persist·레거시 collectWindow·확인 프로브(confirmExhaustionProbe,
// SPEC-COLLECTOR-BACKFILL-010)가 공유하는 검증/파싱 파이프라인을 한 서비스가 소유해야 W-1 불변식(1회 파싱 재사용)이
// 깨지지 않는다 — 분리 시 각 경로가 독립 파싱을 재도입할 위험(REQ-INSERT-005).
@SuppressWarnings("PMD.GodClass")
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

        // 레거시 경로 — ET 당일 가드 없이 전 행 검증 (anchor는 과거 날짜)
        List<ParsedOhlcvRow> kept = keepValidRows(symbol, response.output2());
        if (kept.isEmpty()) {
            return BackfillWindowResult.EMPTY;
        }
        ohlcvInserter.insertBatch(stock.getId(), kept, WatermarkSeries.DAILY_OHLCV_US);
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

        // REQ-OVOH-015: ET 당일 행 제외 후 검증·적재. 폐기 행은 REQ-WM2-007/008 확정성 계측 로그로 남긴다
        // (SPEC-OBSV-WATERMARK-002 §3.4) — 필터링 자체(어느 행을 남기는지)는 변경하지 않는 관찰 전용 부가 동작이다.
        String todayYmd = today.format(DATE_FMT);
        List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> filteredRows =
                response.output2().stream()
                        .filter(
                                row -> {
                                    boolean isEtToday = todayYmd.equals(row.xymd());
                                    if (isEtToday) {
                                        logDiscardedConfirmabilitySnapshot(symbol, row);
                                    }
                                    return !isEtToday;
                                })
                        .toList();
        List<ParsedOhlcvRow> kept = keepValidRows(symbol, filteredRows);
        if (!kept.isEmpty()) {
            // REQ-INSERT-005: 단일 커넥션 배치 INSERT IGNORE (W-2 불변식)
            ohlcvInserter.insertBatch(stock.getId(), kept, WatermarkSeries.DAILY_OHLCV_US);
        }
        return true;
    }

    /**
     * REQ-OVOH-015 ET 당일 가드가 폐기하는 행을 폐기 직전 INFO 로그로 남긴다(REQ-WM2-007/008, SPEC-OBSV-WATERMARK-002
     * §3.4). 저장·검증·멱등성 동작에는 영향 없는 관찰 전용 부수 로그다.
     */
    private void logDiscardedConfirmabilitySnapshot(
            String symbol, KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row) {
        log.info(
                "[overseas-daily][confirmability] symbol={}, xymd={}, open={}, high={}, low={},"
                        + " clos={}, tvol={}, tamt={}",
                symbol,
                row.xymd(),
                row.open(),
                row.high(),
                row.low(),
                row.clos(),
                row.tvol(),
                row.tamt());
    }

    /**
     * 검증 통과 행 목록을 반환한다 — ET 당일 가드 없이 {@link #parseIfValid} 검증만 수행한다 (REQ-INSERT-005, W-1 불변식).
     *
     * <p>ET 당일 가드는 당일 수집 경로({@link #saveValidRows})가 직접 제외한다. 이 메서드는 당일·백필 경로가 공유하는 순수 검증 단계다.
     *
     * @param rows 검증 대상 행 목록 (ET 가드 제외 적용 여부는 호출자가 결정)
     * @return 검증 통과 ParsedOhlcvRow 목록 (없으면 빈 목록)
     */
    // @MX:ANCHOR: [AUTO] keepValidRows — 당일·백필 경로 공유 순수 검증 단계 (fan_in=3)
    // @MX:REASON: REQ-INSERT-005, REQ-OVOH-015 — ET 가드 미포함(호출자가 결정). saveValidRows(ET 가드 후)/
    // fetchWindow(백필, 미적용)/collectWindow(레거시, 미적용) 세 경로가 수렴하는 검증 경계.
    private List<ParsedOhlcvRow> keepValidRows(
            String symbol, List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> rows) {
        List<ParsedOhlcvRow> kept = new ArrayList<>();
        for (KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row : rows) {
            ParsedOhlcvRow parsed = parseIfValid(symbol, row);
            if (parsed != null) {
                kept.add(parsed);
            }
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
    // @MX:NOTE: [AUTO] rawRowCount = 전체 원본 행수(ET 당일 가드 제외 없음)·거부 전. GROUP_A 종료 판정 전용.
    // @MX:REASON: SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-091,-088 / aaa-infra#51 — anchor 날짜 행
    // 오제거 수정
    public OverseasDailyOhlcvFetch fetchWindow(LocalDate anchor, Stock stock, LeaseSession session)
            throws InterruptedException {
        String symbol = stock.getSymbol();
        String excd = stock.getMarket().toDailyPriceExcd();
        KisOverseasDailyOhlcvResponse response = fetch(session, excd, symbol, anchor);

        if (response.output1() == null || response.output2().isEmpty()) {
            return new OverseasDailyOhlcvFetch(List.of(), null, 0, 0);
        }
        // @MX:NOTE: [AUTO] zdiv 가드 조기반환 — 정직한 rawRowCount(response) 반환 (0 하드코딩 아님)
        // @MX:REASON: SPEC-COLLECTOR-BACKFILL-010 §Exclusions [MODIFY] — output2 비지 않음(≥1)이므로 케이스
        // ②(zdiv 이상, rawRowCount>0)가 케이스 ①(진짜 빈 응답, rawRowCount==0)과 판별되어 EMPTY_ANOMALY로 분기된다.
        if (parseZdiv(response.output1().zdiv()) > ZDIV_MAX) {
            return new OverseasDailyOhlcvFetch(List.of(), null, 0, rawRowCount(response));
        }

        int rawRowCount = rawRowCount(response);
        List<ParsedOhlcvRow> kept = keepValidRows(symbol, response.output2());
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
     * [SPEC-COLLECTOR-BACKFILL-010] 종료 확인 프로브 — {@code below} 아래 구간에 거래일이 남아있는지 확인한다
     * (REQ-146/-150).
     *
     * <p>비트랜잭션 fetch 단계 전용(@Transactional 없음) — DB 미접촉, KIS HTTP 1회. {@code below.minusDays(1)}을
     * BYMD로 조회해 응답에 행이 1개 이상 있으면 {@code true}(하한 아래 데이터 잔존, MORE_DATA_EXISTS), 빈 정상 응답이면 {@code
     * false}(소진 확정, CONFIRMED_EXHAUSTED)를 반환한다. 프로브 rows는 저장하지 않는다(검증 전용). API 오류는 호출자(executor)가
     * DEFERRED로 분류하도록 전파한다.
     *
     * @param below 도달 최과거일(oldest) — 이 날짜 아래를 조회한다
     * @param stock 백필 대상 미국 종목
     * @param session per-run 헬스 스냅샷 세션
     * @return 아래 구간에 데이터가 있으면 {@code true}, 빈 정상 응답이면 {@code false}
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    // @MX:NOTE: [AUTO] confirmExhaustionProbe — 비tx 종료 확인 프로브. windows_total 미증가(검증 호출).
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-010
    public boolean confirmExhaustionProbe(LocalDate below, Stock stock, LeaseSession session)
            throws InterruptedException {
        String symbol = stock.getSymbol();
        String excd = stock.getMarket().toDailyPriceExcd();
        KisOverseasDailyOhlcvResponse response = fetch(session, excd, symbol, below.minusDays(1));
        return response.output1() != null && !response.output2().isEmpty();
    }

    /**
     * KIS 원본 응답 행수를 산정한다 — 검증(volume/OHLC) 거부 전 전체 행수(DP-1 해외).
     *
     * <p>해외는 {@code mod_yn}(수정주가 플래그)이 명세에 없어(MI-03) 국내의 {@code modYn} 제외 단계가 없다. ET 당일 가드는 백필 경로에서
     * 적용하지 않는다 — {@code anchor}가 과거 날짜이므로 ET today 가드를 적용하면 첫 행(xymd==anchor)을 항상 제거해 {@code
     * rawRowCount=99 < 100 → COMPLETED} 오종료를 유발한다 (aaa-infra#51). 당일 수집 경로는 {@link #saveValidRows}가
     * ET today 가드를 인라인으로 유지한다. 국내 {@link
     * com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService#rawRowCount}와 동일 패턴.
     */
    private int rawRowCount(KisOverseasDailyOhlcvResponse response) {
        return response.output2().size();
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
        ohlcvInserter.insertBatch(stock.getId(), fetch.rows(), WatermarkSeries.DAILY_OHLCV_US);
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

    /**
     * 일봉 행 검증 규칙 (REQ-OVOH-012, MA-01, REQ-INSERT-005).
     *
     * <p>파싱 성공 시 파싱된 값을 담은 {@link ParsedOhlcvRow}를 반환 — 1회 파싱, 재사용 (W-1 불변식).
     *
     * <ul>
     *   <li>가격(close/open/high/low) ≤ 0 또는 {@code > PRICE_MAX_USD} → empty
     *   <li>거래량 &lt; 0(음수) → empty; 거래정지일(OHLC 유효 + {@code volume == 0})은 허용(저장)
     *   <li>{@code tamt} &lt; 0(음수) → empty(물리적 불가능값 방어); {@code tamt == 0}은 거래정지 행·정상 행({@code
     *       volume > 0}) 모두 허용(aaa-infra#93 — KIS 아카이브({@code HHDFS76240000})가 가격·거래량은 정상인데 {@code
     *       tamt}만 0으로 반환하는 실데이터 행 존재)
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
            // 비분할 사유(규제·변동성·정리매매) 방어. tamt는 음수(물리적 불가능값)만 거부하고 0은 거래정지 행·정상 행
            // 모두 허용(aaa-infra#93 — KIS 아카이브의 실데이터 tamt=0 결측 방지, 국내 정책과 통일).
            // @MX:REASON: SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-090,-093 — 국내와 일관 + 종료 오판 사전 방어.
            boolean invalid = invalidPrice || volume < 0 || tradingValue < 0;

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
            BigDecimal[] clamped = clampOhlc(symbol, row.xymd(), open, high, low, close);
            return new ParsedOhlcvRow(
                    tradeDate, open, clamped[0], clamped[1], close, volume, tradingValue);
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

    /**
     * OHLC 내적 정합성 클램프 (aaa-infra#60).
     *
     * <p><b>배경</b>: 미국 종목 daily_ohlcv에서 {@code high < max(open, close, low)} 또는 {@code low >
     * min(open, close, high)}인 논리 위반 54건이 발견됐다. KIS {@code HHDFS76240000}을 즉시 재호출해 54건 전체를 대조한 결과
     * 100% 동일값이 재현됐다 — 즉 컬렉터 파싱/저장 로직 버그가 아니라 KIS가 실제로 그렇게 반환하는 <b>원본 데이터 결함</b>이다. 40건은 공통 시그니처(당일
     * 세션 실제 고점이 시가와 일치하는 날인데 KIS {@code high}가 시가보다 낮게 산정됨)를 보였고, 이는 장 시작 틱이 KIS 벤더 측 고가 집계에서 누락되는
     * 계산 버그로 추정된다.
     *
     * <p><b>독립 검증(2026-07-03)</b>: Yahoo Finance(비공식 {@code v8/finance/chart})로 54건 전체를 재대조했다.
     * 분할·배당으로 조정된 응답(예: 이후 액면분할된 종목의 과거 시세가 축소되어 반환됨)은 종가 비율로 정규화 후 비교했다. 52/54건(96%)에서 Yahoo 값이
     * KIS와 달랐고, 그 방향은 전부 "위반을 해소하는 쪽"(더 높은 high 또는 더 낮은 low)이었다 — 동일 결함을 재현한 건은 0건. 이는 KIS 벤더 버그
     * 가설을 반증하지 않고 오히려 corroborate한다.
     *
     * <p><b>왜 행을 skip하지 않고 클램프하는가</b>: 처음 검토했던 방식은 "검증 실패로 행 자체를 skip"이었으나, 이 경우 해당 거래일이 통째로 결측되어
     * 이동평균·거래일 연속성 가정·백필 윈도우 종료 판정(카운트 기반) 등에 원래 오류(필드 1개의 미세한 값 오차)보다 더 넓은 부작용을 준다는 것이 확인되어 기각했다.
     * 반면 클램프는 결측 없이 자체 필드(open/close/low 또는 open/close/high)만으로 내적 정합성을 회복한다.
     *
     * <p><b>한계</b>: 클램프는 위반을 없앨 뿐, Yahoo가 알려준 실제값(예: 정확한 고점 233.85)까지 복원하지는 않는다. Yahoo를 정정 소스로 직접
     * 채택하는 방안은 별도로 검토했으나 기각했다 — {@code daily_ohlcv}에 벤더 출처 컬럼이 없어 추적 불가능하고, Yahoo 기본 응답 자체가 분할·배당
     * 조정값이라 ADR-025(원주가 저장 정책)와 충돌하며, 발생빈도(54/699,504 ≈ 0.008%) 대비 상시 폴백 아키텍처 구축 비용이 과하다.
     *
     * <p>반환값은 {@code [clampedHigh, clampedLow]} 순서다. 클램프가 실제로 적용된 경우에만 WARN 로그를 남겨 추적 가능하게 한다.
     */
    private BigDecimal[] clampOhlc(
            String symbol,
            String xymd,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close) {
        BigDecimal correctHigh = open.max(close).max(low);
        BigDecimal correctLow = open.min(close).min(high);
        boolean highViolation = high.compareTo(correctHigh) < 0;
        boolean lowViolation = low.compareTo(correctLow) > 0;

        if (!highViolation && !lowViolation) {
            return new BigDecimal[] {high, low};
        }

        BigDecimal clampedHigh = highViolation ? correctHigh : high;
        BigDecimal clampedLow = lowViolation ? correctLow : low;
        log.warn(
                "[overseas-daily] OHLC 정합성 클램프 적용 (aaa-infra#60, KIS 원본 데이터 결함) — symbol={},"
                        + " date={}, open={}, close={}, high={}→{}, low={}→{}",
                symbol,
                xymd,
                open,
                close,
                high,
                clampedHigh,
                low,
                clampedLow);
        return new BigDecimal[] {clampedHigh, clampedLow};
    }
}
