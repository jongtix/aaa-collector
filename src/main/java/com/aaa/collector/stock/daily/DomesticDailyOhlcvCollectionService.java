package com.aaa.collector.stock.daily;

import com.aaa.collector.backfill.BackfillWindowResult;
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
import java.time.format.DateTimeFormatter;
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
 * 국내 일봉 OHLCV 수집 서비스.
 *
 * <p>활성 관심종목({@code watchlist_removed_at IS NULL})을 대상으로 KIS {@code FHKST03010100} API에서 최근 5거래일
 * 일봉을 수집하여 {@code daily_ohlcv}에 멱등 저장한다.
 *
 * <p>키 선택: {@link GuardedKisExecutor} 단일 게이트를 경유한다. 배치 시작 시 {@link
 * KeyLeaseRegistry#openSession()}으로 per-batch 헬스 스냅샷을 1회 고정(REQ-KISGATE-006a)하고, 종목별 게이트 호출이 그 세션에서
 * least-busy 키를 동적 lease한다 (SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001,-020).
 *
 * <p>모든 키 죽음(REQ-KISGATE-024, REQ-KEYDIST-020 보존): 세션 스냅샷이 비면, per-stock 수집 호출 0회 보장, 전체 skip,
 * ERROR 로그 1회 출력. 전체 키 폴백 없음.
 *
 * <p>실패 경로(REQ-BATCH-025): 특정 키의 token 발급 실패({@link KisTokenIssueException})는 해당 종목을 graceful
 * skip하고 skip 카운터에 집계한다. 영구 비즈니스 오류(인증·파라미터)는 전파한다(REQ-BATCH-024).
 *
 * <p>수집 대상: {@code asset_type IN (STOCK, ETF)} — INDEX 제외(REQ-BATCH3-024). INDEX는 U 전용 API
 * SectorIndexCollectionService가 담당한다.
 *
 * <p>REQ-INSERT-002: 행 검증(isValid) 시 파싱된 결과를 {@link ParsedOhlcvRow}로 반환하여, 불일치 탐지·JDBC 바인딩이 재파싱 없이
 * 재사용한다 (W-1 불변식).
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 국내 일봉 수집 진입점 — 게이트 경유 키 lease·검증·멱등 저장·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-BATCH-001 REQ-BATCH-030,-031,-032,-033,-025,-026,
// SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001,-020,-024
// @MX:SPEC: SPEC-COLLECTOR-BATCH-001, SPEC-COLLECTOR-KISGATE-001
public class DomesticDailyOhlcvCollectionService {

    /**
     * KIS 응답의 최근 5거래일 범위를 캘린더 일수로 넉넉하게 요청할 여유 일수.
     *
     * <p>월요일 기준으로 전주 수·목·금 3거래일을 포함하려면 최소 7일 전이면 충분하나, 연휴 대비 14일로 설정한다.
     */
    private static final int LOOKBACK_CALENDAR_DAYS = 14;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /** 극단값 가격 상한 (₩100,000,000 — 삼성바이오로직스 등 최고가 수준의 10배). */
    private static final BigDecimal PRICE_MAX = new BigDecimal("100000000");

    /** 극단값 거래량 상한 (100억 주). */
    private static final long VOLUME_MAX = 10_000_000_000L;

    private final StockRepository stockRepository;
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /** 불일치 탐지 위임 — 동일 패키지 내 격리로 CouplingBetweenObjects 임계값 유지. */
    private final MismatchDetector mismatchDetector;

    /**
     * 일봉 INSERT IGNORE를 단일 JDBC 커넥션 배치로 실행하고 침묵 드롭 경고를 캡처하는 경로 (REQ-OBSV-023).
     *
     * <p>JPA {@code DailyOhlcvRepository.insertIgnoreDuplicate}를 대체한다 — 동일한 INSERT IGNORE SQL이나
     * JDBC 경고 체인을 노출해 중복 외 침묵 드롭을 가시화할 수 있다.
     */
    private final WarningCountingOhlcvInserter ohlcvInserter;

    /**
     * 국내 일봉 수집을 실행하고 집계 결과를 반환한다.
     *
     * @param today 수집 기준일 (일반적으로 오늘 날짜)
     * @return 시도/성공/skip 종목 수 집계
     */
    public CollectionResult collect(LocalDate today) {
        // REQ-BATCH3-024: per-stock 대상은 STOCK+ETF만 — INDEX 헛호출 제거 (StockRepository 계층에서 캡슐화)
        List<Stock> activeStocks = stockRepository.findAllActiveTradable();

        if (activeStocks.isEmpty()) {
            log.info("[domestic-daily] 수집 대상 없음 — activeStocks=0");
            return new CollectionResult(0, 0, 0);
        }

        // REQ-KISGATE-006a: per-batch 헬스 스냅샷 1회 고정 (selectHealthy 배치당 1회 호출)
        LeaseSession session = keyLeaseRegistry.openSession();

        int total = activeStocks.size();

        // REQ-KISGATE-024, REQ-KEYDIST-020 보존: 빈 스냅샷 = 전 키 사망 → skip-all + ERROR + no fallback
        if (session.isEmpty()) {
            log.error(
                    "[domestic-daily] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
                    total,
                    total);
            return new CollectionResult(total, 0, total);
        }

        LocalDate fromDate = today.minusDays(LOOKBACK_CALENDAR_DAYS);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        // Virtual Thread executor — each stock runs on its own VT, blocking in limiter.consume()
        // without tying up ForkJoinPool.commonPool() threads.
        // 키 선택은 게이트가 세션 스냅샷에서 동적 lease한다(REQ-KISGATE-020). 모든 종목이 동일 세션을 공유한다(REQ-KISGATE-031).
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Stock stock : activeStocks) {
                executor.submit(
                        () -> collectStock(stock, session, fromDate, today, succeeded, skipped));
            }
        } // close() blocks until all submitted tasks complete

        return new CollectionResult(total, succeeded.get(), skipped.get());
    }

    private void collectStock(
            Stock stock,
            LeaseSession session,
            LocalDate fromDate,
            LocalDate toDate,
            AtomicInteger succeeded,
            AtomicInteger skipped) {

        String symbol = stock.getSymbol();
        try {
            KisDailyOhlcvResponse response = fetch(session, symbol, fromDate, toDate);
            saveValidRows(stock, symbol, response);
            succeeded.incrementAndGet();

        } catch (KisRateLimitException | RestClientException e) {
            // REQ-KISGATE-022: retryable 재시도 소진 → graceful skip (기존 BatchResult.skip 등가 종단 동작)
            log.warn(
                    "[domestic-daily] skip (재시도 소진) — symbol={}, reason={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        } catch (InterruptedException e) {
            // RETRY-001 REQ-RETRY-017 보존: 인터럽트 플래그 복원 후 skip (전파 아님)
            Thread.currentThread().interrupt();
            log.warn("[domestic-daily] 인터럽트 — symbol={} skip", symbol);
            skipped.incrementAndGet();
        } catch (NoHealthyKeyException e) {
            // 방어적: step 2에서 단락되므로 정상 운용에서는 도달하지 않음
            log.warn("[domestic-daily] 건강 키 0개로 skip — symbol={}", symbol);
            skipped.incrementAndGet();
        } catch (KisTokenIssueException e) {
            // REQ-BATCH-025 보존: token 발급 실패 → graceful skip
            log.warn(
                    "[domestic-daily] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        }
    }

    /**
     * 백필용 윈도우 1구간 수집 — 당일 수집과 동일한 검증·매핑·INSERT IGNORE 경로를 재사용한다 (REQ-BACKFILL-002).
     *
     * <p>당일 경로({@link #collect(LocalDate)})와 차이는 두 가지뿐이다: (1) {@code (from, to)} 기간을 호출자가 직접 지정해 그룹
     * A 윈도우 SPAN(≥150 달력일, REQ-BACKFILL-013a)을 요청할 수 있고, (2) 종료 판정 입력인 {@link
     * BackfillWindowResult}(최소 거래일 + 행 수)를 반환한다. 검증·매핑·적재 로직은 {@link #fetch}와 {@link
     * #saveValidRows}를 그대로 거치므로 새 파싱 분기를 만들지 않는다.
     *
     * <p>예외는 호출자(T6 오케스트레이터)가 상태 전이(REQ-BACKFILL-030)·항목 격리(REQ-BACKFILL-033)로 처리하도록 전파한다 — 당일 경로처럼
     * skip 카운터로 흡수하지 않는다.
     *
     * @param stock 백필 대상 종목 (활성 관심종목, REQ-BACKFILL-006)
     * @param session 호출자가 1회 고정한 per-run 헬스 스냅샷 세션 (REQ-KISGATE-006a)
     * @param from 윈도우 하단 조회 시작일 ({@code FID_INPUT_DATE_1})
     * @param to 윈도우 상단 조회 종료일 ({@code FID_INPUT_DATE_2}, anchor)
     * @return 적재 대상 행의 최소 거래일 + 행 수 (적재 대상 없으면 {@link BackfillWindowResult#EMPTY})
     * @throws InterruptedException 게이트 호출이 인터럽트되면 전파 (호출자가 복원·처리)
     */
    public BackfillWindowResult collectWindow(
            Stock stock, LeaseSession session, LocalDate from, LocalDate to)
            throws InterruptedException {
        String symbol = stock.getSymbol();
        KisDailyOhlcvResponse response = fetch(session, symbol, from, to);
        List<ParsedOhlcvRow> validRows = saveValidRows(stock, symbol, response);
        if (validRows.isEmpty()) {
            return BackfillWindowResult.EMPTY;
        }
        // ParsedOhlcvRow.tradeDate()로 최소 거래일 도출 — 문자열 재파싱 없음 (REQ-INSERT-002)
        LocalDate oldest =
                validRows.stream()
                        .map(ParsedOhlcvRow::tradeDate)
                        .min(LocalDate::compareTo)
                        .orElseThrow();
        return new BackfillWindowResult(oldest, validRows.size());
    }

    private KisDailyOhlcvResponse fetch(
            LeaseSession session, String symbol, LocalDate fromDate, LocalDate toDate)
            throws InterruptedException {
        String from = fromDate.format(DATE_FMT);
        String to = toDate.format(DATE_FMT);
        return guardedKisExecutor.execute(
                session,
                uri ->
                        uri.path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                .queryParam("FID_INPUT_ISCD", symbol)
                                .queryParam("FID_INPUT_DATE_1", from)
                                .queryParam("FID_INPUT_DATE_2", to)
                                .queryParam("FID_PERIOD_DIV_CODE", "D")
                                .queryParam("FID_ORG_ADJ_PRC", "1")
                                .build(),
                "FHKST03010100",
                KisDailyOhlcvResponse.class);
    }

    /**
     * 수정주가 제외·검증·불일치 탐지만 수행하고 INSERT하지 않는다.
     *
     * <p>REQ-INSERT-002: 각 행을 1회만 파싱하여 {@link ParsedOhlcvRow}로 수집한다.
     *
     * @return 검증 통과 행 목록 (없으면 빈 목록)
     */
    private List<ParsedOhlcvRow> filterAndValidate(
            Stock stock, String symbol, KisDailyOhlcvResponse response) {
        List<ParsedOhlcvRow> validRows =
                response.output2().stream()
                        .filter(row -> !"Y".equals(row.modYn()))
                        .map(row -> parseIfValid(symbol, row))
                        .filter(parsed -> parsed != null)
                        .toList();
        if (validRows.isEmpty()) {
            return validRows;
        }
        // REQ-OHLCV2-010,-011: 불일치 탐지 위임 — ParsedOhlcvRow 재파싱 없이 전달 (REQ-INSERT-003)
        mismatchDetector.detectAndLog(stock.getId(), symbol, validRows);
        return validRows;
    }

    /**
     * 검증 통과 행을 멱등 적재하고 그 행들을 반환한다.
     *
     * <p>당일 경로({@link #collectStock})는 반환값을 무시하고, 백필 경로({@link #collectWindow})는 최소 거래일·행 수 도출에
     * 사용한다 — 동일 검증·매핑·적재 경로를 공유한다(REQ-BACKFILL-002).
     *
     * @return 적재한 검증 통과 행 목록 (없으면 빈 목록)
     */
    private List<ParsedOhlcvRow> saveValidRows(
            Stock stock, String symbol, KisDailyOhlcvResponse response) {
        List<ParsedOhlcvRow> validRows = filterAndValidate(stock, symbol, response);
        if (validRows.isEmpty()) {
            return validRows;
        }
        // REQ-OBSV-023: 한 종목의 유효 행들을 단일 커넥션 배치 INSERT IGNORE로 적재하고 침묵 드롭 경고를 캡처한다.
        // REQ-INSERT-004: ParsedOhlcvRow를 바인딩에 직접 사용 — 추가 파싱 없음.
        ohlcvInserter.insertBatch(stock.getId(), validRows);
        return validRows;
    }

    /**
     * [T2] fetch 단계 — HTTP 호출·수정주가 제외·검증·불일치 탐지를 수행하고 INSERT하지 않는다.
     *
     * <p>{@code anchor}(= FID_INPUT_DATE_2)와 {@code from}(= FID_INPUT_DATE_1)을 호출자가 직접 지정한다. 검증 통과
     * 행과 최소 거래일을 {@link DomesticDailyOhlcvFetch}로 반환한다. DB 접촉 없음.
     *
     * @param from 윈도우 하단 조회 시작일 (FID_INPUT_DATE_1, BackfillWindowExecutor가 계산해서 전달)
     * @param anchor 윈도우 상단 조회 종료일 (FID_INPUT_DATE_2)
     * @param stock 백필 대상 종목
     * @param session 호출자가 고정한 per-run 헬스 스냅샷 세션
     * @return 검증 통과 행 목록 + 최소 거래일 + 행 수 (없으면 rows=빈목록, oldestTradeDate=null, rowCount=0)
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    // @MX:NOTE: [AUTO] fetchWindow — 비tx HTTP 단계. DB 미접촉. BackfillWindowExecutor가 @Transactional
    // persistWindow와 교차 빈으로 순차 호출.
    // @MX:NOTE: [AUTO] rawRowCount = KIS 원본 응답 행수(modYn 제외 후·검증 거부 전) — GROUP_A 종료 판정 전용.
    // 거래정지 거부가 종료를 흔들지 않도록 rowCount(저장 행수)와 분리한다.
    // @MX:REASON: SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-082,-088
    public DomesticDailyOhlcvFetch fetchWindow(
            LocalDate from, LocalDate anchor, Stock stock, LeaseSession session)
            throws InterruptedException {
        String symbol = stock.getSymbol();
        KisDailyOhlcvResponse response = fetch(session, symbol, from, anchor);
        int rawRowCount = rawRowCount(response);
        List<ParsedOhlcvRow> validRows = filterAndValidate(stock, symbol, response);
        if (validRows.isEmpty()) {
            return new DomesticDailyOhlcvFetch(List.of(), null, 0, rawRowCount);
        }
        LocalDate oldest =
                validRows.stream()
                        .map(ParsedOhlcvRow::tradeDate)
                        .min(LocalDate::compareTo)
                        .orElseThrow();
        return new DomesticDailyOhlcvFetch(validRows, oldest, validRows.size(), rawRowCount);
    }

    /**
     * KIS 원본 응답 행수를 산정한다 — 수정주가행({@code modYn="Y"}) 제외 후·검증(volume/OHLC) 거부 전 행수(DP-1).
     *
     * <p>{@code modYn} 제외는 별개 정당 필터이므로 종료 판정 입력에서도 제외한다. 검증 거부는 카운트에서 제외하지 않는다 — 거래정지 거부가 종료를 흔들지
     * 않게 하는 것이 본 산정의 목적이다(SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-082).
     */
    private int rawRowCount(KisDailyOhlcvResponse response) {
        return (int) response.output2().stream().filter(row -> !"Y".equals(row.modYn())).count();
    }

    /**
     * [T2] persist 단계 — {@link DomesticDailyOhlcvFetch}의 행을 INSERT IGNORE 배치 적재한다.
     *
     * <p>@Transactional 없음 — 트랜잭션은 {@code BackfillWindowExecutor}(T7)가 소유한다. INSERT 로직은 서비스에
     * 잔류한다(REQ-TXBOUNDARY-002).
     *
     * @param stock 백필 대상 종목
     * @param fetch fetchWindow가 반환한 DTO
     * @return 적재 대상 행의 최소 거래일 + 행 수 (fetch가 빈 경우 {@link BackfillWindowResult#EMPTY})
     */
    // @MX:NOTE: [AUTO] persistWindow — BackfillWindowExecutor @Transactional에 MANDATORY 전파로 합류.
    // INSERT + 결과 구성 담당.
    @Transactional(propagation = Propagation.MANDATORY)
    public BackfillWindowResult persistWindow(Stock stock, DomesticDailyOhlcvFetch fetch) {
        if (fetch.rows().isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[domestic-daily][backfill] persistWindow 스킵 (빈 fetch) — symbol={}",
                        stock.getSymbol());
            }
            return BackfillWindowResult.EMPTY;
        }
        // REQ-INSERT-004: ParsedOhlcvRow를 직접 바인딩 — fmt 파라미터 불필요
        ohlcvInserter.insertBatch(stock.getId(), fetch.rows());
        // SPEC-COLLECTOR-BACKFILL-006: rawRowCount(원본 행수)를 GROUP_A 종료 입력으로 전달. rowCount(저장 행수) 보존.
        return new BackfillWindowResult(
                fetch.oldestTradeDate(), fetch.rowCount(), fetch.rawRowCount());
    }

    /**
     * 일봉 행 검증 규칙 (REQ-BATCH-033, REQ-INSERT-002).
     *
     * <p>파싱 성공 시 파싱된 값을 담은 {@link ParsedOhlcvRow}를 반환한다 — 검증·불일치·바인딩이 동일 객체를 재사용하여 중복 파싱을 제거한다 (W-1
     * 불변식).
     *
     * <ul>
     *   <li>가격(close/open/high/low) ≤ 0 또는 극단값 초과 → empty
     *   <li>거래량 &lt; 0(음수) 또는 극단값 초과 → empty; {@code volume == 0}은 거래정지일로 허용(저장)
     *   <li>null / 빈 문자열 필드 → empty (NumberFormatException 처리)
     * </ul>
     *
     * <p>SPEC-COLLECTOR-BACKFILL-006: 액면분할 거래정지일은 OHLC 유효 + {@code volume == 0}으로 반환되며 실제 거래가 정지된
     * 사실이다(데이터 누락 아님). {@code trading_value}는 검증식에 미포함이라 {@code 0}도 그대로 저장된다.
     */
    private ParsedOhlcvRow parseIfValid(String symbol, KisDailyOhlcvResponse.DailyOhlcvRow row) {
        try {
            BigDecimal close = new BigDecimal(row.stckClpr());
            BigDecimal open = new BigDecimal(row.stckOprc());
            BigDecimal high = new BigDecimal(row.stckHgpr());
            BigDecimal low = new BigDecimal(row.stckLwpr());
            long volume = Long.parseLong(row.acmlVol());
            long tradingValue = Long.parseLong(row.acmlTrPbmn());

            // @MX:NOTE: [AUTO] 거래정지일(volume==0) 허용 — 데이터 누락이 아니라 실제 거래정지 사실. 음수만 거부.
            // @MX:REASON: SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-080,-085 — 액면분할 변경상장일 직전
            // 거래정지 3거래일이 KIS 원주가로 close 고정·volume=0으로 반환됨(실측). volume<=0 거부 시 백필 오종료.
            boolean invalid =
                    close.compareTo(BigDecimal.ZERO) <= 0
                            || open.compareTo(BigDecimal.ZERO) <= 0
                            || high.compareTo(BigDecimal.ZERO) <= 0
                            || low.compareTo(BigDecimal.ZERO) <= 0
                            || close.compareTo(PRICE_MAX) > 0
                            || volume < 0
                            || volume > VOLUME_MAX;

            if (invalid) {
                log.warn(
                        "[domestic-daily] 검증 실패 (데이터 유실) — symbol={}, date={}, close={}, open={}, high={}, low={}, volume={}",
                        symbol,
                        row.stckBsopDate(),
                        row.stckClpr(),
                        row.stckOprc(),
                        row.stckHgpr(),
                        row.stckLwpr(),
                        row.acmlVol());
                return null;
            }
            LocalDate tradeDate = LocalDate.parse(row.stckBsopDate(), DATE_FMT);
            return new ParsedOhlcvRow(tradeDate, open, high, low, close, volume, tradingValue);
        } catch (NumberFormatException e) {
            log.warn(
                    "[domestic-daily] 숫자 파싱 실패 (데이터 유실) — symbol={}, date={}, close={}, open={}, high={}, low={}, volume={}",
                    symbol,
                    row.stckBsopDate(),
                    row.stckClpr(),
                    row.stckOprc(),
                    row.stckHgpr(),
                    row.stckLwpr(),
                    row.acmlVol());
            return null;
        }
    }
}
